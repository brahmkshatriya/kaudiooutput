#include "kaudiooutput.h"
#include <pulse/pulseaudio.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

typedef struct device { char *id, *name, *kind; struct device *next; } device;
struct kao_context {
    pa_threaded_mainloop *loop;
    pa_context *pulse;
    device *devices, *pending;
    char *selected, *default_id, *error;
    kao_changed changed;
    void *user;
    int ready, closing, listing, list_again;
};

typedef struct { char *data; size_t size, capacity; } buffer;
static void append(buffer *b, const char *s) {
    size_t n = strlen(s);
    if (b->size + n + 1 > b->capacity) {
        b->capacity = (b->size + n + 1) * 2;
        b->data = realloc(b->data, b->capacity);
        if (!b->data) abort();
    }
    memcpy(b->data + b->size, s, n + 1); b->size += n;
}
static void quoted(buffer *b, const char *s) {
    if (!s) { append(b, "null"); return; }
    append(b, "\"");
    for (const unsigned char *p = (const unsigned char *)s; *p; p++) {
        char out[8];
        if (*p == '"' || *p == '\\') { out[0] = '\\'; out[1] = *p; out[2] = 0; }
        else if (*p < 32) snprintf(out, sizeof out, "\\u%04x", *p);
        else { out[0] = *p; out[1] = 0; }
        append(b, out);
    }
    append(b, "\"");
}
static char *snapshot(kao_context *c) {
    buffer b = {0}; append(&b, "{\"devices\":[");
    int first = 1;
    for (device *d = c->devices; d; d = d->next) {
        if (!first) append(&b, ",");
        first = 0;
        append(&b, "{\"id\":"); quoted(&b, d->id);
        append(&b, ",\"name\":"); quoted(&b, d->name);
        append(&b, ",\"kind\":"); quoted(&b, d->kind); append(&b, "}");
    }
    append(&b, "],\"selected\":"); quoted(&b, c->selected);
    append(&b, ",\"default\":"); quoted(&b, c->default_id);
    append(&b, ",\"selection\":\"Direct\",\"ready\":"); append(&b, c->ready ? "true" : "false");
    append(&b, ",\"error\":"); quoted(&b, c->error); append(&b, "}"); return b.data;
}
static void emit(kao_context *c) {
    if (!c->closing && c->changed) { char *json = snapshot(c); c->changed(c->user, json); free(json); }
}
static void free_devices(device *d) {
    while (d) { device *next = d->next; free(d->id); free(d->name); free(d->kind); free(d); d = next; }
}
static void drop(pa_operation *op) { if (op) pa_operation_unref(op); }
static int contains(kao_context *c, const char *id) {
    for (device *d = c->devices; d; d = d->next) if (!strcmp(d->id, id)) return 1;
    return 0;
}
static void moved(pa_context *p, int success, void *user) {
    kao_context *c = user;
    if (!success && pa_context_get_state(p) == PA_CONTEXT_READY) {
        free(c->error); c->error = strdup(pa_strerror(pa_context_errno(p))); emit(c);
    }
}
static void route_input(pa_context *p, const pa_sink_input_info *input, int end, void *user) {
    kao_context *c = user;
    if (end || !input || c->closing) return;
    const char *pid = pa_proplist_gets(input->proplist, PA_PROP_APPLICATION_PROCESS_ID);
    char own_pid[32];
    snprintf(own_pid, sizeof own_pid, "%lu", (unsigned long)getpid());
    if (!pid || strcmp(pid, own_pid)) return;
    const char *target = c->selected ? c->selected : c->default_id;
    if (target) drop(pa_context_move_sink_input_by_name(p, input->index, target, moved, c));
}
static void route_all(kao_context *c) {
    if (c->ready) drop(pa_context_get_sink_input_info_list(c->pulse, route_input, c));
}
static void list_devices(kao_context *c);
static void sink(pa_context *p, const pa_sink_info *info, int end, void *user) {
    (void)p; kao_context *c = user;
    if (c->closing) return;
    if (end) {
        if (end > 0) {
            free_devices(c->devices); c->devices = c->pending; c->pending = NULL;
            if (c->selected && !contains(c, c->selected)) { free(c->selected); c->selected = NULL; route_all(c); }
            emit(c);
        } else { free_devices(c->pending); c->pending = NULL; }
        c->listing = 0;
        if (c->list_again) { c->list_again = 0; list_devices(c); }
        return;
    }
    if (!info) return;
    device *d = calloc(1, sizeof *d); d->id = strdup(info->name); d->name = strdup(info->description ? info->description : info->name);
    const char *bus = pa_proplist_gets(info->proplist, PA_PROP_DEVICE_BUS);
    const char *port = info->active_port ? info->active_port->name : "";
    const char *kind = bus && !strcmp(bus, "bluetooth") ? "Bluetooth" :
        bus && !strcmp(bus, "usb") ? "Usb" : strstr(port, "hdmi") ? "Hdmi" :
        strstr(port, "headphone") ? "Headphones" : (info->flags & PA_SINK_HARDWARE) ? "Speaker" : "Virtual";
    d->kind = strdup(kind); d->next = c->pending; c->pending = d;
}
static void list_devices(kao_context *c) {
    if (c->listing) { c->list_again = 1; return; }
    c->listing = 1; drop(pa_context_get_sink_info_list(c->pulse, sink, c));
}
static void server(pa_context *p, const pa_server_info *info, void *user) {
    (void)p; kao_context *c = user;
    if (!info || c->closing) return;
    int changed = (!c->default_id != !info->default_sink_name) ||
        (c->default_id && info->default_sink_name && strcmp(c->default_id, info->default_sink_name));
    free(c->default_id); c->default_id = info->default_sink_name ? strdup(info->default_sink_name) : NULL;
    if (changed && !c->selected) route_all(c);
    emit(c);
}
static void subscribed(pa_context *p, pa_subscription_event_type_t type, uint32_t index, void *user) {
    kao_context *c = user;
    if (c->closing) return;
    switch (type & PA_SUBSCRIPTION_EVENT_FACILITY_MASK) {
        case PA_SUBSCRIPTION_EVENT_SINK: list_devices(c); break;
        case PA_SUBSCRIPTION_EVENT_SERVER: drop(pa_context_get_server_info(p, server, c)); break;
        case PA_SUBSCRIPTION_EVENT_SINK_INPUT:
            /* Only new streams: moving a stream itself emits CHANGE. */
            if ((type & PA_SUBSCRIPTION_EVENT_TYPE_MASK) == PA_SUBSCRIPTION_EVENT_NEW)
                drop(pa_context_get_sink_input_info(p, index, route_input, c));
            break;
        default: break;
    }
}
static void connection(pa_context *p, void *user) {
    kao_context *c = user;
    switch (pa_context_get_state(p)) {
        case PA_CONTEXT_READY:
            c->ready = 1;
            pa_context_set_subscribe_callback(p, subscribed, c);
            drop(pa_context_subscribe(p, PA_SUBSCRIPTION_MASK_SINK | PA_SUBSCRIPTION_MASK_SINK_INPUT | PA_SUBSCRIPTION_MASK_SERVER, NULL, NULL));
            list_devices(c); drop(pa_context_get_server_info(p, server, c)); break;
        case PA_CONTEXT_FAILED:
            c->ready = 0; free(c->error); c->error = strdup(pa_strerror(pa_context_errno(p))); emit(c); break;
        default: break;
    }
}
kao_context *kao_create(kao_changed changed, void *user) {
    kao_context *c = calloc(1, sizeof *c); if (!c) return NULL;
    c->changed = changed; c->user = user; c->loop = pa_threaded_mainloop_new();
    if (!c->loop) { free(c); return NULL; }
    c->pulse = pa_context_new(pa_threaded_mainloop_get_api(c->loop), "KAudioOutput");
    if (!c->pulse) { pa_threaded_mainloop_free(c->loop); free(c); return NULL; }
    pa_context_set_state_callback(c->pulse, connection, c);
    if (pa_context_connect(c->pulse, NULL, PA_CONTEXT_NOFLAGS, NULL) < 0 || pa_threaded_mainloop_start(c->loop) < 0) {
        pa_context_unref(c->pulse); pa_threaded_mainloop_free(c->loop); free(c); return NULL;
    }
    return c;
}
void kao_destroy(kao_context *c) {
    if (!c) return;
    pa_threaded_mainloop_lock(c->loop); c->closing = 1;
    pa_context_set_state_callback(c->pulse, NULL, NULL); pa_context_set_subscribe_callback(c->pulse, NULL, NULL);
    pa_context_disconnect(c->pulse); pa_threaded_mainloop_unlock(c->loop);
    pa_threaded_mainloop_stop(c->loop); pa_context_unref(c->pulse); pa_threaded_mainloop_free(c->loop);
    free_devices(c->devices); free_devices(c->pending); free(c->selected); free(c->default_id); free(c->error); free(c);
}
char *kao_snapshot(kao_context *c) {
    if (!c) return NULL;
    pa_threaded_mainloop_lock(c->loop); char *json = snapshot(c); pa_threaded_mainloop_unlock(c->loop); return json;
}
int kao_select(kao_context *c, const char *id) {
    if (!c) return 0;
    pa_threaded_mainloop_lock(c->loop);
    if (!c->ready || (id && !contains(c, id))) { pa_threaded_mainloop_unlock(c->loop); return 0; }
    free(c->selected); c->selected = id ? strdup(id) : NULL; free(c->error); c->error = NULL;
    route_all(c); emit(c); pa_threaded_mainloop_unlock(c->loop); return 1;
}
int kao_show_picker(kao_context *c) { (void)c; return 0; }
void kao_free(void *memory) { free(memory); }
