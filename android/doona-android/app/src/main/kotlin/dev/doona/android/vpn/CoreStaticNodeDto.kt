package dev.doona.android.vpn

import dev.doona.android.engine.StaticNode
import dev.doona.android.engine.StaticNodeDraft
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@Serializable
internal class StaticNodeRowsDto(val rows: List<StaticNodeRowDto>) {
    fun toRows() = rows.map(StaticNodeRowDto::toRow)
}

@Serializable
internal class StaticNodeDto(val id: String, val name: String, val link: String) {
    fun toStaticNode() = StaticNode(id, name, link)

    companion object {
        fun of(node: StaticNode) = StaticNodeDto(node.id, node.name, node.link)
    }
}

@Serializable
internal class StaticNodeBatchArgs(val rows: List<StaticNodeDraftDto>)

@Serializable
internal class StaticNodeUpdateArgs(val expected: StaticNodeDto, val node: StaticNodeDraftDto)

@Serializable
internal class StaticNodeRemoveArgs(val expected: StaticNodeDto, val confirmed: Boolean)

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("kind")
@Serializable
internal sealed interface StaticNodeDraftDto {
    @Serializable
    @SerialName("link")
    class Link(val link: String, val name: String? = null) : StaticNodeDraftDto

    @Serializable
    @SerialName("manual")
    class Manual(
        val protocol: String,
        val server: String,
        val port: Int,
        val name: String? = null,
        val username: String? = null,
        val password: String? = null,
        val uuid: String? = null,
        val cipher: String? = null,
        val options: Map<String, String> = emptyMap(),
    ) : StaticNodeDraftDto

    companion object {
        fun of(draft: StaticNodeDraft): StaticNodeDraftDto = when (draft) {
            is StaticNodeDraft.Link -> Link(draft.link, draft.name)

            is StaticNodeDraft.Manual -> Manual(
                draft.protocol, draft.server, draft.port, draft.name, draft.username, draft.password, draft.uuid, draft.cipher, draft.options,
            )
        }
    }
}
