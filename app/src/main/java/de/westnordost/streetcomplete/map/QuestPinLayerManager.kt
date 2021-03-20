package de.westnordost.streetcomplete.map

import android.content.res.Resources
import android.os.Build
import androidx.collection.LongSparseArray
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.mapzen.tangram.MapData
import com.mapzen.tangram.geometry.Point
import de.westnordost.osmapi.map.data.OsmLatLon
import de.westnordost.streetcomplete.data.quest.*
import de.westnordost.streetcomplete.data.visiblequests.OrderedVisibleQuestTypesProvider
import de.westnordost.streetcomplete.ktx.values
import de.westnordost.streetcomplete.map.tangram.toLngLat
import de.westnordost.streetcomplete.quests.bikeway.AddCycleway
import de.westnordost.streetcomplete.util.*
import kotlinx.coroutines.*
import java.util.*
import javax.inject.Inject

/** Manages the layer of quest pins in the map view:
 *  Gets told by the QuestsMapFragment when a new area is in view and independently pulls the quests
 *  for the bbox surrounding the area from database and holds it in memory. */
class QuestPinLayerManager @Inject constructor(
    private val questTypesProvider: OrderedVisibleQuestTypesProvider,
    private val resources: Resources,
    private val visibleQuestsSource: VisibleQuestsSource
): LifecycleObserver, VisibleQuestsSource.Listener {

    // draw order in which the quest types should be rendered on the map
    private val questTypeOrders: MutableMap<QuestType<*>, Int> = mutableMapOf()
    // all the (zoom 14) tiles that have been retrieved from DB into memory already
    private val retrievedTiles: MutableSet<TilePos> = mutableSetOf()
    // last displayed rect of (zoom 14) tiles
    private var lastDisplayedRect: TilesRect? = null

    // quest group -> ( quest Id -> [point, ...] )
    private val quests: EnumMap<QuestGroup, LongSparseArray<List<Point>>> = EnumMap(QuestGroup::class.java)

    private val lifecycleScope = CoroutineScope(SupervisorJob())

    lateinit var mapFragment: MapFragment

    var questsLayer: MapData? = null
        set(value) {
            if (field === value) return
            field = value
            updateLayer()
        }

    /** Switch visibility of quest pins layer */
    var isVisible: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            updateLayer()
        }

    init {
        visibleQuestsSource.addListener(this)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START) fun onStart() {
        /* When reentering the fragment, the database may have changed (quest download in
        * background or change in settings), so the quests must be pulled from DB again */
        initializeQuestTypeOrders()
        clear()
        onNewScreenPosition()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP) fun onStop() {
        clear()
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY) fun onDestroy() {
        questsLayer = null
        visibleQuestsSource.removeListener(this)
        lifecycleScope.cancel()
    }

    fun onNewScreenPosition() {
        val zoom = mapFragment.cameraPosition?.zoom ?: return
        if (zoom < TILES_ZOOM) return
        val displayedArea = mapFragment.getDisplayedArea() ?: return
        val tilesRect = displayedArea.enclosingTilesRect(TILES_ZOOM)
        if (lastDisplayedRect != tilesRect) {
            lastDisplayedRect = tilesRect
            updateQuestsInRect(tilesRect)
        }
    }

    override fun onUpdatedVisibleQuests(added: Collection<Quest>, removed: Collection<Long>, group: QuestGroup) {
        var updates = 0
        added.forEach { if (add(it, group)) updates++ }
        removed.forEach { if (remove(it, group)) updates++ }
        if (updates > 0) updateLayer()
    }

    override fun onVisibleQuestsInvalidated() {
        clear()
        onNewScreenPosition()
    }

    private fun updateQuestsInRect(tilesRect: TilesRect) {
        // area too big -> skip (performance)
        if (tilesRect.size > 4) {
            return
        }
        var tiles: List<TilePos>
        synchronized(retrievedTiles) {
            tiles = tilesRect.asTilePosSequence().filter { !retrievedTiles.contains(it) }.toList()
        }
        val minRect = tiles.minTileRect() ?: return
        val bbox = minRect.asBoundingBox(TILES_ZOOM)
        val questTypeNames = questTypesProvider.get().map { it::class.simpleName!! }
        lifecycleScope.launch(Dispatchers.IO) {
            visibleQuestsSource.getAllVisible(bbox, questTypeNames).forEach {
                add(it.quest, it.group)
            }
            updateLayer()
        }
        synchronized(retrievedTiles) { retrievedTiles.addAll(tiles) }
    }

    private fun add(quest: Quest, group: QuestGroup): Boolean {
        val positions = quest.markerLocations
        val previousPoints = synchronized(quest) { quests[group]?.get(quest.id!!) }
        val previousPositions = previousPoints?.map { OsmLatLon(it.coordinateArray[1], it.coordinateArray[0]) }
        if (positions == previousPositions) return false

        // hack away cycleway quests for old Android SDK versions (#713)
        if (quest.type is AddCycleway && Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return false
        }
        val questIconName = resources.getResourceEntryName(quest.type.icon)
        val points = positions.map { position ->
            val properties = mapOf(
                "type" to "point",
                "kind" to questIconName,
                "importance" to getQuestImportance(quest).toString(),
                MARKER_QUEST_GROUP to group.name,
                MARKER_QUEST_ID to quest.id!!.toString()
            )
            Point(position.toLngLat(), properties)
        }
        synchronized(quests) {
            quests.getOrPut(group, { LongSparseArray(256) }).put(quest.id!!, points)
        }
        return true
    }

    private fun remove(questId: Long, group: QuestGroup): Boolean {
        synchronized(quests) {
            if (quests[group]?.containsKey(questId) != true) return false

            quests[group]?.remove(questId)
            return true
        }
    }

    private fun clear() {
        synchronized(quests) {
            for (value in quests.values) {
                value.clear()
            }
        }
        synchronized(retrievedTiles) {
            retrievedTiles.clear()
        }
        questsLayer?.clear()
        lastDisplayedRect = null
    }

    private fun updateLayer() {
        if (isVisible) {
            questsLayer?.setFeatures(getPoints())
        } else {
            questsLayer?.clear()
        }
    }

    private fun getPoints(): List<Point> {
        synchronized(quests) {
            return quests.values.flatMap { questsById ->
                questsById.values.flatten()
            }
        }
    }

    private fun initializeQuestTypeOrders() {
        // this needs to be reinitialized when the quest order changes
        var order = 0
        for (questType in questTypesProvider.get()) {
            questTypeOrders[questType] = order++
        }
    }

    /** returns values from 0 to 100000, the higher the number, the more important */
    private fun getQuestImportance(quest: Quest): Int {
        val questTypeOrder = questTypeOrders[quest.type] ?: 0
        val freeValuesForEachQuest = 100000 / questTypeOrders.size
        /* quest ID is used to add values unique to each quest to make ordering consistent
           freeValuesForEachQuest is an int, so % freeValuesForEachQuest will fit into int */
        val hopefullyUniqueValueForQuest = ((quest.id?: 0) % freeValuesForEachQuest).toInt()
        return 100000 - questTypeOrder * freeValuesForEachQuest + hopefullyUniqueValueForQuest
    }

    companion object {
        const val MARKER_QUEST_ID = "quest_id"
        const val MARKER_QUEST_GROUP = "quest_group"
        private const val TILES_ZOOM = 14
    }
}
