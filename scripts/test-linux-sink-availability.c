#include <assert.h>
#include <pulse/pulseaudio.h>

// Exercise the native sink filter directly without requiring an audio server.
#include "../lib/src/nativeInterop/cinterop/kaudio_linux.c"

int main(void) {
    pa_sink_info sink = {0};
    pa_sink_port_info port = {0};

    // Sinks without port information must not disappear.
    assert(sink_is_available(&sink));

    sink.active_port = &port;
    port.available = PA_PORT_AVAILABLE_NO;
    assert(!sink_is_available(&sink));

    port.available = PA_PORT_AVAILABLE_YES;
    assert(sink_is_available(&sink));

    // Unknown availability (e.g. virtual outputs) is not disconnected.
    port.available = PA_PORT_AVAILABLE_UNKNOWN;
    assert(sink_is_available(&sink));
    return 0;
}
