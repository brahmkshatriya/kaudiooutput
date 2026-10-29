#ifndef KAUDIOOUTPUT_H
#define KAUDIOOUTPUT_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct kao_context kao_context;
/* JSON is borrowed for the callback's duration. Callbacks stop before destroy returns. */
typedef void (*kao_changed)(void *user, const char *json);
kao_context *kao_create(kao_changed changed, void *user);
void kao_destroy(kao_context *context);
/* Owned UTF-8 JSON; release with kao_free. */
char *kao_snapshot(kao_context *context);
void kao_free(void *memory);
/* null restores the system default. Returns zero on immediate rejection. */
int kao_select(kao_context *context, const char *device_id);
int kao_show_picker(kao_context *context);
#ifdef __cplusplus
}
#endif
#endif
