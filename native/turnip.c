// Загрузка Vulkan-драйвера Turnip (Adreno) через libadrenotools. Отдельная библиотека: libglfw.so остаётся чистой C-библиотекой
// (статический libstdc++ и libadrenotools внутри libglfw.so ломали OSMesa/Zink: их символы перекрывали символы Mesa).
// libglfw.so подгружает эту библиотеку через dlopen(RTLD_LOCAL) только при выборе «Zink + Turnip».
#include <dlfcn.h>
#include <stdio.h>
#include <sys/stat.h>
#include <adrenotools/driver.h>

__attribute__((visibility("default")))
void* mobilix_open_turnip(const char* turnip_dir, const char* lib_name, const char* hook_dir, const char* tmp_dir) {
    void* mapping = NULL;
    char redir[512]; snprintf(redir, sizeof redir, "%s/redirect/", turnip_dir); mkdir(redir, 0700);
    char tmp[512]; snprintf(tmp, sizeof tmp, "%s/", tmp_dir && tmp_dir[0] ? tmp_dir : "/data/local/tmp");
    char dir[512]; snprintf(dir, sizeof dir, "%s/", turnip_dir);
    char hook[512]; snprintf(hook, sizeof hook, "%s/", hook_dir);
    return adrenotools_open_libvulkan(RTLD_NOW | RTLD_GLOBAL, ADRENOTOOLS_DRIVER_CUSTOM, tmp, hook, dir, lib_name, redir, &mapping);
}
