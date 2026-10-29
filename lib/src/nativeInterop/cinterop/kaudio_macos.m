#include "kaudiooutput.h"
#import <Foundation/Foundation.h>
#import <CoreAudio/CoreAudio.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

struct kao_context {
    pthread_mutex_t mutex;
    char *selected;
    kao_changed changed;
    void *user;
    dispatch_queue_t queue;
    AudioObjectPropertyListenerBlock listener;
};
static AudioObjectPropertyAddress address(AudioObjectPropertySelector selector, AudioObjectPropertyScope scope) {
    AudioObjectPropertyAddress a = {selector, scope, kAudioObjectPropertyElementMain}; return a;
}
static NSString *string_property(AudioObjectID object, AudioObjectPropertySelector selector) {
    AudioObjectPropertyAddress a = address(selector, kAudioObjectPropertyScopeGlobal);
    CFStringRef value = NULL; UInt32 size = sizeof value;
    if (AudioObjectGetPropertyData(object, &a, 0, NULL, &size, &value) != noErr || !value) return nil;
    return CFBridgingRelease(value);
}
static NSArray *devices(void) {
    AudioObjectPropertyAddress a = address(kAudioHardwarePropertyDevices, kAudioObjectPropertyScopeGlobal);
    UInt32 size = 0;
    if (AudioObjectGetPropertyDataSize(kAudioObjectSystemObject, &a, 0, NULL, &size) != noErr) return @[];
    AudioDeviceID *ids = malloc(size);
    if (!ids) return @[];
    if (AudioObjectGetPropertyData(kAudioObjectSystemObject, &a, 0, NULL, &size, ids) != noErr) { free(ids); return @[]; }
    NSMutableArray *result = [NSMutableArray array];
    for (unsigned i = 0; i < size / sizeof(AudioDeviceID); ++i) {
        AudioObjectPropertyAddress streams = address(kAudioDevicePropertyStreams, kAudioDevicePropertyScopeOutput);
        UInt32 streamSize = 0;
        if (AudioObjectGetPropertyDataSize(ids[i], &streams, 0, NULL, &streamSize) != noErr || !streamSize) continue;
        NSString *uid = string_property(ids[i], kAudioDevicePropertyDeviceUID);
        NSString *name = string_property(ids[i], kAudioObjectPropertyName);
        if (!uid.length || !name.length) continue;
        UInt32 transport = 0, transportSize = sizeof transport;
        AudioObjectPropertyAddress t = address(kAudioDevicePropertyTransportType, kAudioObjectPropertyScopeGlobal);
        AudioObjectGetPropertyData(ids[i], &t, 0, NULL, &transportSize, &transport);
        NSString *kind = transport == kAudioDeviceTransportTypeBluetooth || transport == kAudioDeviceTransportTypeBluetoothLE ? @"Bluetooth" :
            transport == kAudioDeviceTransportTypeUSB ? @"Usb" : transport == kAudioDeviceTransportTypeHDMI ? @"Hdmi" :
            transport == kAudioDeviceTransportTypeAirPlay ? @"AirPlay" : transport == kAudioDeviceTransportTypeBuiltIn ? @"Speaker" :
            transport == kAudioDeviceTransportTypeVirtual ? @"Virtual" : @"Other";
        [result addObject:@{@"id":uid, @"name":name, @"kind":kind}];
    }
    free(ids); return result;
}
char *kao_snapshot(kao_context *c) {
    if (!c) return NULL;
    @autoreleasepool {
        pthread_mutex_lock(&c->mutex);
        NSArray *available = devices();
        NSString *selected = c->selected ? [NSString stringWithUTF8String:c->selected] : nil;
        if (selected && ![[available valueForKey:@"id"] containsObject:selected]) { free(c->selected); c->selected = NULL; selected = nil; }
        AudioDeviceID defaultId = 0; UInt32 size = sizeof defaultId;
        AudioObjectPropertyAddress a = address(kAudioHardwarePropertyDefaultOutputDevice, kAudioObjectPropertyScopeGlobal);
        AudioObjectGetPropertyData(kAudioObjectSystemObject, &a, 0, NULL, &size, &defaultId);
        NSString *defaultUID = defaultId ? string_property(defaultId, kAudioDevicePropertyDeviceUID) : nil;
        NSDictionary *snapshot = @{@"devices":available, @"selected":selected ?: NSNull.null,
            @"default":defaultUID ?: NSNull.null, @"selection":@"Direct", @"ready":@YES};
        NSData *json = [NSJSONSerialization dataWithJSONObject:snapshot options:0 error:nil];
        char *value = json ? strndup(json.bytes, json.length) : NULL;
        pthread_mutex_unlock(&c->mutex); return value;
    }
}
static void emit(kao_context *c) {
    char *json = kao_snapshot(c);
    if (json && c->changed) c->changed(c->user, json);
    free(json);
}
kao_context *kao_create(kao_changed changed, void *user) {
    kao_context *c = calloc(1, sizeof *c); if (!c) return NULL;
    pthread_mutex_init(&c->mutex, NULL); c->changed = changed; c->user = user;
    c->queue = dispatch_queue_create("KAudioOutput.devices", DISPATCH_QUEUE_SERIAL);
    c->listener = ^(UInt32 count, const AudioObjectPropertyAddress *properties) { (void)count; (void)properties; emit(c); };
    AudioObjectPropertyAddress a = address(kAudioHardwarePropertyDevices, kAudioObjectPropertyScopeGlobal);
    OSStatus first = AudioObjectAddPropertyListenerBlock(kAudioObjectSystemObject, &a, c->queue, c->listener);
    a = address(kAudioHardwarePropertyDefaultOutputDevice, kAudioObjectPropertyScopeGlobal);
    OSStatus second = AudioObjectAddPropertyListenerBlock(kAudioObjectSystemObject, &a, c->queue, c->listener);
    if (first != noErr || second != noErr) { kao_destroy(c); return NULL; }
    return c;
}
int kao_select(kao_context *c, const char *id) {
    if (!c) return 0;
    @autoreleasepool {
        pthread_mutex_lock(&c->mutex);
        if (id && ![[devices() valueForKey:@"id"] containsObject:[NSString stringWithUTF8String:id]]) { pthread_mutex_unlock(&c->mutex); return 0; }
        free(c->selected); c->selected = id ? strdup(id) : NULL;
        pthread_mutex_unlock(&c->mutex); emit(c); return 1;
    }
}
void kao_destroy(kao_context *c) {
    if (!c) return;
    AudioObjectPropertyAddress a = address(kAudioHardwarePropertyDevices, kAudioObjectPropertyScopeGlobal);
    AudioObjectRemovePropertyListenerBlock(kAudioObjectSystemObject, &a, c->queue, c->listener);
    a = address(kAudioHardwarePropertyDefaultOutputDevice, kAudioObjectPropertyScopeGlobal);
    AudioObjectRemovePropertyListenerBlock(kAudioObjectSystemObject, &a, c->queue, c->listener);
    dispatch_sync(c->queue, ^{});
    c->listener = nil; c->queue = nil;
    pthread_mutex_destroy(&c->mutex); free(c->selected); free(c);
}
int kao_show_picker(kao_context *c) { (void)c; return 0; }
void kao_free(void *memory) { free(memory); }
