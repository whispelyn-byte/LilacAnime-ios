#include "LilacLocalAI.h"
#include <xlocale.h>

struct LilacLocaleScope { locale_t previous; locale_t current; };

extern "C" void *lilac_utf8_locale_begin() {
    locale_t current = newlocale(LC_CTYPE_MASK, "en_US.UTF-8", nullptr);
    if (!current) current = newlocale(LC_CTYPE_MASK, "UTF-8", nullptr);
    if (!current) return nullptr;
    locale_t previous = uselocale(current);
    if (!previous) { freelocale(current); return nullptr; }
    return new LilacLocaleScope{previous, current};
}

extern "C" void lilac_utf8_locale_end(void *token) {
    if (!token) return;
    auto *scope = static_cast<LilacLocaleScope *>(token);
    uselocale(scope->previous);
    freelocale(scope->current);
    delete scope;
}
