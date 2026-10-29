#include "kaudiooutput.h"
#include <windows.h>
#include <shellapi.h>
#include <stdlib.h>
#include <string.h>

struct kao_context { int placeholder; };
kao_context *kao_create(kao_changed changed, void *user) { (void)changed; (void)user; return calloc(1, sizeof(kao_context)); }
void kao_destroy(kao_context *c) { free(c); }
char *kao_snapshot(kao_context *c) {
    (void)c;
    return _strdup("{\"devices\":[],\"selection\":\"SystemPicker\",\"ready\":true}");
}
int kao_select(kao_context *c, const char *id) { (void)c; (void)id; return 0; }
int kao_show_picker(kao_context *c) {
    (void)c;
    /* Documented per-app output selection UI; never changes the global default. */
    return (INT_PTR)ShellExecuteW(NULL, L"open", L"ms-settings:apps-volume", NULL, NULL, SW_SHOWNORMAL) > 32;
}
void kao_free(void *memory) { free(memory); }
