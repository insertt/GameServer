package pl.margoj.server.implementation.npc

import com.google.common.collect.ImmutableList
import pl.margoj.server.api.Server
import pl.margoj.server.api.map.ImmutableLocation
import pl.margoj.server.api.player.Gender
import pl.margoj.server.api.player.Profession
import pl.margoj.server.api.utils.splitByChar
import pl.margoj.server.implementation.entity.EntityImpl
import pl.margoj.server.implementation.map.TownImpl
import pl.margoj.server.implementation.npc.parser.parsed.NpcParsedScript
import pl.margoj.server.implementation.npc.parser.parsed.ScriptContext
import java.util.Collections
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

class Npc(val script: NpcParsedScript?, override val location: ImmutableLocation, override val server: Server) : EntityImpl()
{
    var id: Int = npcIdCounter.incrementAndGet()

    override var name: String = ""
    override val direction: Int = 0
    override var icon: String = ""
    override var gender: Gender = Gender.UNKNOWN

    override var level: Int
        get() = this.stats.level
        set(value)
        {
            this.stats.level = value
        }

    override val stats = NpcData(this)
    override var hp: Int = 100
    override var deadUntil: Date? = null

    var group: Int = 0
    var type: NpcType = NpcType.NPC
    var subType: NpcSubtype = NpcSubtype.NORMAL
    var customSpawnTime: Long? = null

    override val withGroup: List<EntityImpl>
        get()
        {
            if (this.group <= 0)
            {
                return Collections.singletonList(this)
            }

            val out = ImmutableList.builder<EntityImpl>()
            out.add(this)

            val town = this.location.town!! as TownImpl

            for (npc in town.npc)
            {
                if (npc.type == NpcType.MONSTER && npc.group == this.group && npc != this && npc.battleUnavailabilityCause == null)
                {
                    out.add(npc)
                }
            }

            return out.build()
        }

    override fun kill()
    {
        this.deadUntil = Date(System.currentTimeMillis() + this.killTime)
    }

    override val killTime: Long
        get()
        {
            return (this.customSpawnTime ?: super.killTime)
        }

    fun loadData()
    {
        val dataBlock = script?.getNpcCodeBlock("dane") ?: return
        val context = ScriptContext(null, this)
        context.delegate = this::delegateData
        dataBlock.execute(context)
    }

    private fun delegateData(function: String, parameters: Array<Any>, context: ScriptContext)
    {
        when (function)
        {
            "grafika" -> this.icon = parameters[0] as String
            "nazwa" -> this.name = parameters[0] as String
            "poziom", "level" -> this.stats.level = (parameters[0] as Long).toInt()
            "npc" -> this.type = NpcType.NPC
            "potwór" -> this.type = NpcType.MONSTER
            "typ" ->
            {
                this.subType = when (parameters[0] as String)
                {
                    "normalny", "zwykły" -> NpcSubtype.NORMAL
                    "elita", "elitaI", "elita1", "e1", "eI" -> NpcSubtype.ELITE1
                    "elitaII", "elita2", "e2", "eII" -> NpcSubtype.ELITE2
                    "elitaIII", "elita3", "e3", "eIII" -> NpcSubtype.ELITE3
                    "hero", "heros" -> NpcSubtype.HERO
                    "titan", "tytan" -> NpcSubtype.TITAN
                    else -> NpcSubtype.NORMAL
                }
            }
            "płeć" ->
            {
                this.gender = when (parameters[0] as String)
                {
                    "m", "mężczyzna" -> Gender.MALE
                    "k", "kobieta" -> Gender.FEMALE
                    "x", "nieokreślona", "nieznana" -> Gender.UNKNOWN
                    else -> Gender.UNKNOWN
                }
            }
            "profesja" ->
            {
                this.stats.profession = when (parameters[0] as String)
                {
                    "w", "wojownik" -> Profession.WARRIOR
                    "p", "paladyn" -> Profession.PALADIN
                    "b", "tancerz ostrzy" -> Profession.BLADE_DANCER
                    "m", "mag" -> Profession.MAGE
                    "h", "łowca" -> Profession.HUNTER
                    "t", "tropiciel" -> Profession.TRACKER
                    else -> Profession.WARRIOR
                }
            }
            "siła", "str" -> this.stats.strength = (parameters[0] as Long).toInt()
            "zręczność", "agi" -> this.stats.agility = (parameters[0] as Long).toInt()
            "intelekt", "int" -> this.stats.intellect = (parameters[0] as Long).toInt()
            "sa" -> this.stats.attackSpeed = (parameters[0] as Long).toDouble() / 100.0
            "hp" -> this.stats.maxHp = (parameters[0] as Long).toInt()
            "atak" ->
            {
                val values = (parameters[0] as String).splitByChar('-')
                this.stats.damage = values[0].toInt()..values[1].toInt()
            }
            "pancerz" -> this.stats.armor = (parameters[0] as Long).toInt()
            "blok" -> this.stats.block = (parameters[0] as Long).toInt()
            "unik" -> this.stats.block = (parameters[0] as Long).toInt()
        }
    }

    override fun toString(): String
    {
        return "Npc(id=$id, name=$name, location=$location)"
    }

    companion object
    {
        private val npcIdCounter = AtomicInteger()
    }
}