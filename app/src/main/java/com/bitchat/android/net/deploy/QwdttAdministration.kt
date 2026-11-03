package com.bitchat.android.net.deploy

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.URLEncoder

/** Contains bearer credentials. Never log, persist, or save in instance state. */
class QwdttAccess(
    val password: String,
    val label: String,
    val hash: String,
    val devices: Int,
    val expiresAt: Long,
    val revoked: Boolean,
    val received: Long,
    val sent: Long,
) {
    override fun toString() = "QwdttAccess([redacted])"

    fun subscription(host: String): String {
        PreparationTarget(host, 22)
        require(password.isNotBlank() && hash.isNotBlank())
        val fields =
            linkedMapOf(
                "name" to label.ifBlank { "qWDTT" },
                "peer" to host,
                "hashes" to hash,
                "pass" to password,
                "workers" to "2",
                "port" to "1080",
            )
        return "qwdtt://config?" +
            fields.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }
    }
}

class QwdttAdminRequest(val action: String, val fields: Map<String, String> = emptyMap()) {
    init {
        require(
            action in
                setOf(
                    "list",
                    "create",
                    "update",
                    "activate",
                    "deactivate",
                    "delete",
                    "unbind-device",
                )
        )
        require(
            fields.keys.all {
                it in setOf("password", "vk_hash", "label", "days", "max_devices", "device_id")
            }
        )
        require(fields.values.all { it.length <= 4096 && it.none(Char::isISOControl) })
        fields["days"]?.let { require((it.toIntOrNull() ?: 0) in 1..365) }
        fields["max_devices"]?.let { require((it.toIntOrNull() ?: 0) in 1..32) }
        fields["label"]?.let { require(it.length <= 90) }
        if (action == "list") require(fields.isEmpty())
        if (action == "create") require(!fields["vk_hash"].isNullOrBlank())
        if (action !in setOf("list", "create")) require(!fields["password"].isNullOrBlank())
    }

    override fun toString() = "QwdttAdminRequest([redacted])"
}

object QwdttAdministration {
    // API token and CA stay on the server. SSH verifies the server before any credentials.
    // No redirects, proxy environment, arbitrary endpoints or user-supplied shell commands.
    internal val script =
        """
        import json, pathlib, ssl, sys, urllib.request
        payload=json.load(sys.stdin)
        action=payload['action']
        paths={'list':'','create':'','update':'/update','activate':'/activate','deactivate':'/deactivate','delete':'/delete','unbind-device':'/unbind-device'}
        assert action in paths
        import urllib.parse
        body=None if action=='list' else urllib.parse.urlencode(payload['fields']).encode()
        config=pathlib.Path('/etc/panchan/qwdtt')
        context=ssl.create_default_context(cafile=str(config/'admin.crt'))
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args,**kwargs): return None
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),urllib.request.HTTPSHandler(context=context))
        request=urllib.request.Request('https://localhost:56443/admin/passwords'+paths[action],data=body,headers={'Authorization':'Bearer '+(config/'admin-token').read_text().strip()})
        try:
            with opener.open(request,timeout=15) as response:
                data=response.read(32769)
                assert len(data)<=32768
                sys.stdout.write(data.decode())
        except Exception:
            sys.exit(1)
        """
            .trimIndent()

    fun execute(connection: PreparationConnection, request: QwdttAdminRequest): String {
        val payload =
            Gson()
                .toJson(mapOf("action" to request.action, "fields" to request.fields))
                .toByteArray()
        return try {
            connection.execute("python3 -c " + quoteSshCommand(script), payload)
        } finally {
            payload.fill(0)
        }
    }

    fun parse(json: String): List<QwdttAccess> {
        require(json.length <= 32768)
        val root = JsonParser.parseString(json).asJsonObject
        val entries = root.getAsJsonArray("passwords") ?: return emptyList()
        require(entries.size() <= 10)
        return entries.map { value ->
            val item = value.asJsonObject
            fun text(key: String) =
                item
                    .get(key)
                    ?.takeUnless { it.isJsonNull }
                    ?.asString
                    .orEmpty()
                    .also { require(it.length <= 4096) }
            fun number(key: String) = item.get(key)?.takeUnless { it.isJsonNull }?.asLong ?: 0L
            QwdttAccess(
                text("password"),
                text("label").take(90),
                text("vk_hash"),
                number("max_devices").toInt(),
                number("expires_at"),
                item.get("is_deactivated")?.asBoolean ?: false,
                number("down_bytes"),
                number("up_bytes"),
            )
        }
    }
}
