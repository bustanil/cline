package bot.cline.jetbrains.bridge

import bot.cline.host.proto.GetHostVersionResponse
import bot.cline.host.proto.GetTelemetrySettingsResponse
import bot.cline.host.proto.Setting
import bot.cline.host.proto.TelemetrySettingsEvent
import bot.cline.proto.Empty
import bot.cline.proto.EmptyRequest
import bot.cline.proto.StringRequest
import io.grpc.ServerServiceDefinition
import bot.cline.proto.String as ProtoString

/** `host.EnvService`: the IDE's name and version, clipboard and telemetry setting. */
class EnvService(
    private val ide: IdeBridge,
    private val pluginVersion: String = "0.1.0",
) {
    fun definition(): ServerServiceDefinition =
        HostService("host.EnvService")
            .unary("clipboardWriteText", StringRequest.getDefaultInstance(), Empty.getDefaultInstance()) { request ->
                ide.clipboardWrite(request.value)
                Empty.getDefaultInstance()
            }
            .unary("clipboardReadText", EmptyRequest.getDefaultInstance(), ProtoString.getDefaultInstance()) {
                ProtoString.newBuilder().setValue(ide.clipboardRead()).build()
            }
            .unary("getHostVersion", EmptyRequest.getDefaultInstance(), GetHostVersionResponse.getDefaultInstance()) {
                val (platform, platformVersion) = ide.hostVersion()
                GetHostVersionResponse.newBuilder()
                    .setPlatform(platform)
                    .setVersion(platformVersion)
                    .setClineType("Cline for JetBrains")
                    .setClineVersion(pluginVersion)
                    .build()
            }
            .unary("getIdeRedirectUri", EmptyRequest.getDefaultInstance(), ProtoString.getDefaultInstance()) {
                ProtoString.getDefaultInstance()
            }
            .unary("getTelemetrySettings", EmptyRequest.getDefaultInstance(), GetTelemetrySettingsResponse.getDefaultInstance()) {
                GetTelemetrySettingsResponse.newBuilder().setIsEnabled(Setting.UNSUPPORTED).build()
            }
            .serverStreaming("subscribeToTelemetrySettings", EmptyRequest.getDefaultInstance(), TelemetrySettingsEvent.getDefaultInstance()) { _, out ->
                // The setting never changes; keep the stream open until the core cancels it.
                out.onNext(TelemetrySettingsEvent.newBuilder().setIsEnabled(Setting.UNSUPPORTED).build())
            }
            .unary("shutdown", EmptyRequest.getDefaultInstance(), Empty.getDefaultInstance()) {
                ide.shutdownRequested()
                Empty.getDefaultInstance()
            }
            .unary("debugLog", StringRequest.getDefaultInstance(), Empty.getDefaultInstance()) { Empty.getDefaultInstance() }
            .unary("openExternal", StringRequest.getDefaultInstance(), Empty.getDefaultInstance()) { request ->
                ide.openExternal(request.value)
                Empty.getDefaultInstance()
            }
            .build()
}
