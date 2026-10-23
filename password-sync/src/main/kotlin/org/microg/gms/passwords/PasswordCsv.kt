/* SPDX-License-Identifier: Apache-2.0 */
package org.microg.gms.passwords

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Google Password Manager CSV columns. Cell contents are exact, never spreadsheet-escaped. */
object PasswordCsv {
    private val columns = listOf("name", "url", "username", "password", "note")
    const val MAX_BYTES = PasswordArchive.MAX_BYTES
    fun encode(passwords: List<PasswordData>): ByteArray {
        if (passwords.size > 1000 || passwords.any { it.blocked }) throw ProtocolException()
        val out = StringBuilder()
        fun row(values: List<String>) {
            out.append(values.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }).append("\r\n")
            if (out.length > MAX_BYTES / 4) throw ProtocolException()
        }
        row(columns)
        for (data in passwords) row(listOf(data.displayName, data.origin.ifEmpty { data.realm }, data.username, data.password, data.defaultNote?.value.orEmpty()))
        return out.toString().toByteArray(Charsets.UTF_8).also { if (it.size > MAX_BYTES) throw ProtocolException() }
    }
    fun decode(bytes: ByteArray, nowMillis: Long): List<PasswordData> {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw ProtocolException()
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
        catch (_: Exception) { throw ProtocolException() }
        val rows = parse(text)
        val header = rows.firstOrNull() ?: throw ProtocolException()
        if (header.size != header.toSet().size || header.any { it !in columns } || !header.containsAll(listOf("url", "username", "password"))) throw ProtocolException()
        val result = rows.drop(1).map { row ->
            if (row.size != header.size) throw ProtocolException()
            val values = header.zip(row).toMap()
            val url = values["url"] ?: throw ProtocolException()
            val username = values["username"] ?: throw ProtocolException()
            val password = values["password"]?.takeIf { it.isNotEmpty() } ?: throw ProtocolException()
            val data = if (url.startsWith("android://")) {
                AffiliationFacet.parse(url)
                PasswordData.create(url, url, username, password, nowMillis)
            } else PasswordData.create(PasswordData.webRealm(url), url, username, password, nowMillis)
            data.withDisplayName(values["name"].orEmpty()).withDefaultNote(values["note"].orEmpty(), nowMillis)
        }
        if (result.map(PasswordData::tag).toSet().size != result.size) throw ConflictException()
        return result
    }
    private fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var offset = 0; var quoted = false; var closed = false; var atStart = true
        fun finishCell() { row.add(cell.toString()); cell.setLength(0); closed = false; atStart = true
            if (row.size > columns.size) throw ProtocolException() }
        fun finishRow() { finishCell(); rows.add(row); row = ArrayList()
            if (rows.size > 1001) throw ProtocolException() }
        while (offset < text.length) {
            val char = text[offset++]
            if (quoted) {
                if (char == '"') {
                    if (offset < text.length && text[offset] == '"') { cell.append('"'); offset++ }
                    else { quoted = false; closed = true }
                } else cell.append(char)
            } else when (char) {
                '"' -> { if (!atStart || closed) throw ProtocolException(); quoted = true; atStart = false }
                ',' -> finishCell()
                '\r', '\n' -> { if (char == '\r') { if (offset >= text.length || text[offset++] != '\n') throw ProtocolException() }; finishRow() }
                else -> { if (closed) throw ProtocolException(); cell.append(char); atStart = false }
            }
            if (cell.length > 100_000) throw ProtocolException()
        }
        if (quoted) throw ProtocolException()
        if (row.isNotEmpty() || cell.isNotEmpty() || closed || !atStart) finishRow()
        return rows
    }
}
