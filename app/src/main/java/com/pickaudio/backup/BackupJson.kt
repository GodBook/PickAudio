package com.pickaudio.backup

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

/** Reject ambiguous objects and excessive nesting before reflection constructs model instances. */
internal fun validateBackupJson(input: Reader) {
    JsonReader(input).use { reader ->
        reader.setStrictness(Strictness.STRICT)
        var values = 0
        fun read(depth: Int) {
            require(depth <= 32 && ++values <= 2_000_000) { "备份 JSON 结构过于复杂" }
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    val names = HashSet<String>()
                    while (reader.hasNext()) {
                        require(names.add(reader.nextName())) { "备份 JSON 字段重复" }
                        read(depth + 1)
                    }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    while (reader.hasNext()) read(depth + 1)
                    reader.endArray()
                }
                JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                JsonToken.BOOLEAN -> reader.nextBoolean()
                JsonToken.NULL -> reader.nextNull()
                else -> error("备份 JSON 内容无效")
            }
        }
        read(0)
        require(reader.peek() == JsonToken.END_DOCUMENT) { "备份包含多份 JSON" }
    }
}
