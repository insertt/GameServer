package pl.margoj.server.implementation.player

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import pl.margoj.mrf.item.ItemCategory
import pl.margoj.mrf.item.ItemProperties
import pl.margoj.mrf.item.MargoItem
import pl.margoj.mrf.map.Point
import pl.margoj.mrf.map.metadata.pvp.MapPvP
import pl.margoj.mrf.map.metadata.welcome.WelcomeMessage
import pl.margoj.mrf.map.objects.gateway.GatewayObject
import pl.margoj.server.api.map.Location
import pl.margoj.server.api.utils.Parse
import pl.margoj.server.api.utils.TimeUtils
import pl.margoj.server.api.utils.splitByChar
import pl.margoj.server.implementation.item.ItemLocation
import pl.margoj.server.implementation.map.TownImpl
import pl.margoj.server.implementation.network.protocol.IncomingPacket
import pl.margoj.server.implementation.network.protocol.NetworkManager
import pl.margoj.server.implementation.network.protocol.OutgoingPacket
import pl.margoj.server.implementation.network.protocol.PacketHandler
import pl.margoj.server.implementation.network.protocol.jsons.ItemObject
import pl.margoj.server.implementation.network.protocol.jsons.TownObject
import pl.margoj.server.implementation.utils.GsonUtils
import java.util.concurrent.CopyOnWriteArrayList

class PlayerConnection(val manager: NetworkManager, val aid: Int) : PacketHandler
{
    private val logger = manager.server.logger
    private val packetModifiers = CopyOnWriteArrayList<(OutgoingPacket) -> Unit>()
    private var disconnectReason: String? = null
    private var disposed: Boolean = false
    private var lastEvent: Double = 0.0

    var lastPacket: Long = 0
    var ip: String? = null
    var player: PlayerImpl? = null
        private set

    init
    {
        logger.trace("New connection object create for aid=$aid")
    }

    override fun handle(packet: IncomingPacket, out: OutgoingPacket)
    {
        this.lastPacket = System.currentTimeMillis()
        val query = packet.queryParams

        if (this.disposed)
        {
            out.addEngineAction(OutgoingPacket.EngineAction.RELOAD)
            return
        }

        if (this.disconnectReason != null)
        {
            out.addEngineAction(OutgoingPacket.EngineAction.STOP)
            out.addWarn(this.disconnectReason!!)
            this.disconnectReason = null
            return
        }

        val ev = packet.queryParams["ev"]

        if (ev != null)
        {
            try
            {
                if (ev.toDouble() < this.lastEvent)
                {
                    out.addWarn("Odrzucono stare zapytanie - nowsze już zostało przetworzone")
                    out.addEvent()
                    out.markAsOk()
                    return
                }
            }
            catch(e: NumberFormatException)
            {
                this.checkForMaliciousData(true, "Invalid 'ev' received")
                return
            }
        }

        if (packet.type == "init")
        {
            val initlvl = Parse.parseInt(query["initlvl"])
            if (initlvl != null)
            {
                this.handleInit(initlvl, out)
            }
        }

        if (this.player != null)
        {
            this.handlePlayer(this.player!!, packet, out)
        }

        if (query.containsKey("ev"))
        {
            this.lastEvent = TimeUtils.getTimestampDouble()
            out.addEvent(this.lastEvent)
        }

        this.packetModifiers.forEach { it(out) }

        this.packetModifiers.clear()
    }

    private fun handleInit(initlvl: Int, out: OutgoingPacket)
    {
        logger.trace("handleInit, initlvl=$initlvl, aid=$aid")
        val gson = GsonUtils.gson

        when (initlvl)
        {
            1 ->
            {
                val j = out.json

                if (this.player == null)
                {
                    this.player = PlayerImpl(this.aid, "aid$aid", this.manager.server, this)
                    this.manager.server.entityManager.registerEntity(player!!)
                    val location = this.player!!.location
                    location.town = this.manager.server.getTownById("pierwsza_mapa") // TODO
                    location.x = 8
                    location.y = 13
                }
                else
                {
                    this.player!!.entityTracker.reset()
                }

                val town = this.player!!.location.town!! as TownImpl

                j.add("town", gson.toJsonTree(TownObject(
                        mapId = town.numericId,
                        mainMapId = 0,
                        width = town.width,
                        height = town.height,
                        imageFileName = "${town.id}.png",
                        mapName = town.name,
                        pvp = town.getMetadata(MapPvP::class.java).margonemId,
                        water = "",
                        battleBackground = "aa1.jpg",
                        welcomeMessage = town.getMetadata(WelcomeMessage::class.java).value
                )))

                val gw2 = JsonArray()
                val townname = JsonObject()

                for (mapObject in town.objects)
                {
                    val gateway = mapObject as? GatewayObject ?: continue
                    val targetMap = player!!.server.getTownById(gateway.targetMap) ?: continue

                    gw2.add(targetMap.numericId)
                    gw2.add(gateway.position.x)
                    gw2.add(gateway.position.y)
                    gw2.add(0) // TODO needs key
                    gw2.add(0) // TODO: min level & max level

                    townname.add(targetMap.numericId.toString(), JsonPrimitive(targetMap.name))
                }

                j.add("gw2", gw2)
                j.add("townname", townname)
                j.addProperty("worldname", this.manager.server.config.serverConfig!!.name)
                j.addProperty("time", TimeUtils.getTimestampLong())
                j.addProperty("tutorial", -1)
                j.addProperty("clientver", 1461248638)

                j.add("h", gson.toJsonTree(this.player!!.data.createHeroObject()))
            }
            2 -> // collisions
            {
                out.json.addProperty("cl", (this.player!!.location.town!! as TownImpl).margonemCollisionsString)
            }
            3 -> // items
            {
                val testItem = MargoItem("torba", "TorbaZZZ")
                testItem[ItemProperties.CATEGORY] = ItemCategory.BAGS

                out.addItem(ItemObject(
                        id = 1,
                        name = testItem.name,
                        own = this.player!!.id,
                        location = ItemLocation.PLAYERS_INVENTORY.margoType,
                        icon = "bag/torba12.gif",
                        x = 0,
                        y = 0,
                        itemType = testItem[ItemProperties.CATEGORY].margoId,
                        price = 123456789,
                        slot = 20,
                        statistics = "bag=42;permbound;soulbound;legendary"
                ))

                val testItem2 = MargoItem("torba", "Torba w torbie!!!")
                testItem2[ItemProperties.CATEGORY] = ItemCategory.NEUTRAL

                out.addItem(ItemObject(
                        id = 2,
                        name = testItem2.name,
                        own = this.player!!.id,
                        location = ItemLocation.PLAYERS_INVENTORY.margoType,
                        icon = "bag/torba12.gif",
                        x = 0,
                        y = 0,
                        itemType = testItem2[ItemProperties.CATEGORY].margoId,
                        price = 0,
                        slot = 0,
                        statistics = "opis=Torba w torbie!;amount=10;capacity=64;legendary;artefact;resdmg=1"
                ))
            }
            4 -> // finish
            {
                out.addEvent()
            }
        }

        out.markAsOk()
    }


    private fun handlePlayer(player: PlayerImpl, packet: IncomingPacket, out: OutgoingPacket)
    {
        val query = packet.queryParams

        // handle direction, has to be handled before movemenet
        val pdir = query["pdir"]
        if (pdir != null)
        {
            val intDirection = Parse.parseInt(pdir)
            this.checkForMaliciousData(intDirection == null || intDirection < 0 || intDirection > 3, "invalid direction")
            player.movementManager.playerDirection = intDirection!!
        }

        val ml = query["ml"]
        val mts = query["mts"]

        if (ml != null && mts != null)
        {
            val moveList = ml.splitByChar(';')
            val moveTimestamps = mts.splitByChar(';')

            this.checkForMaliciousData(moveList.isEmpty() || moveList.size != moveTimestamps.size, "ml.size() != mts.size()")

            for (i in 0..(moveList.size - 1))
            {
                val move = moveList[i]
                val moveSplit = move.splitByChar(',')
                this.checkForMaliciousData(moveSplit.size != 2, "invalid move format")

                val x = Parse.parseInt(moveSplit[0])
                val y = Parse.parseInt(moveSplit[1])
                var timestamp: Double?

                try
                {
                    timestamp = moveTimestamps[i].toDouble()
                }
                catch(e: NumberFormatException)
                {
                    timestamp = null
                }

                this.checkForMaliciousData(x == null || y == null || timestamp == null, "invalid move format")

                player.movementManager.queueMove(x!!, y!!, timestamp!!)
            }
        }

        if (packet.type == "chat")
        {
            val c = packet.body["c"] ?: query["c"]
            this.checkForMaliciousData(c == null, "no chat message present")
            this.manager.server.chatManager.handle(player, c!!)
        }

        if (packet.type == "console")
        {
            val custom = query["custom"]
            this.checkForMaliciousData(custom == null, "no command provided")
            this.manager.server.commandsManager.dispatchCommand(player, custom!!)
        }

        val move = player.movementManager.processMove()

        if (move != null)
        {
            out.addMove(move.x, move.y)
        }

        player.entityTracker.handlePacket(out)

        if (packet.type == "walk")
        {
            val gateway = (player.location.town as? TownImpl)?.getObject(Point(player.location.x, player.location.y)) as? GatewayObject ?: return
            val targetMap = player.server.getTownById(gateway.targetMap)

            if (targetMap == null || !targetMap.inBounds(gateway.target))
            {
                player.logToConsole("unknown or invalid map: ${gateway.targetMap}")
                logger.warn("unknown or invalid map: ${gateway.targetMap} at ${gateway.position}")
                return
            }

            player.teleport(Location(targetMap, gateway.target.x, gateway.target.y))
        }

        out.markAsOk()
    }

    override fun disconnect(reason: String)
    {
        this.disconnectReason = reason
    }

    fun addModifier(modifier: (OutgoingPacket) -> Unit)
    {
        this.packetModifiers.add(modifier)
    }

    fun noReturn(): Int
    {
        throw Exception("no")
    }

    fun abc()
    {
        noReturn()

        val a = 5
    }

    fun dispose()
    {
        this.player = null
        this.disposed = true
    }

    private fun checkForMaliciousData(condition: Boolean, info: String)
    {
        if (condition)
        {
            this.disconnect("Malicious packet")
            throw IllegalArgumentException("Malicius packet: " + info)
        }
    }
}