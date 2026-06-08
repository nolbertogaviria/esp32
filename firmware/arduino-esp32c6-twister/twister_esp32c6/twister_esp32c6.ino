#include "twister_protocol.h"
#include <SPI.h>
#include <Adafruit_GFX.h>
#include <Adafruit_ST7789.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLESecurity.h>
#include <Fonts/FreeSans12pt7b.h>
#include <Fonts/FreeSans18pt7b.h>
#include <Fonts/FreeSansBold12pt7b.h>

#define TWISTER_HAS_ST7789 1

#define BLE_DEVICE_NAME  "Twister"
#define BLE_SVC_UUID     "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define BLE_CHAR_RX_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"  // Android escribe
#define BLE_CHAR_TX_UUID "beb5483f-36e1-4688-b7f5-ea07361b26a8"  // ESP32 notifica

// Pines OFICIALES segun datasheet Spotpear ESP32-C6-LCD-1.47
// Fuente: https://spotpear.com/wiki/ESP32-C6-1.47-inch-LCD-Display-Screen-LVGL-SD-WIFI6-ST7789.html
static const int LCD_PIN_MOSI = 6;
static const int LCD_PIN_SCLK = 7;
static const int LCD_PIN_CS   = 14;
static const int LCD_PIN_DC   = 15;
static const int LCD_PIN_RST  = 21;
static const int LCD_PIN_BL   = 22;

#if TWISTER_HAS_ST7789
Adafruit_ST7789 tft(LCD_PIN_CS, LCD_PIN_DC, LCD_PIN_RST);
#endif

static BLECharacteristic *pTxChar = nullptr;
static bool ble_connected = false;
static volatile bool splash_requested = false;
static bool          layout_ready      = false;  // true tras el primer play_splash_animation()

// ── Cola de notificaciones ──────────────────────────────────────────────────
#define NOTIF_QUEUE_SIZE  5
#define NOTIF_SCROLL_MS   4000UL   // ms entre paginas

static twister_notif_t notif_queue[NOTIF_QUEUE_SIZE];
static uint8_t        notif_queue_count  = 0;
static uint8_t        notif_display_idx  = 0;
static unsigned long  notif_last_page_ms = 0;

// ── Variables de Reloj/Fecha ────────────────────────────────────────────────
static char          system_date[33]      = "";
static char          system_time[16]      = "";
static bool          system_time_received = false;
static uint32_t      system_time_seconds  = 0;  // Segundos del día (0..86399)
static char          last_drawn_date[33]  = "";
static char          last_drawn_time[16]  = "";
static unsigned long last_time_tick_ms    = 0;

// ── Actualizaciones pendientes desde la tarea BLE (evita race condition en SPI/TFT) ──
static volatile bool     turn_pending        = false;
static volatile bool     notif_pending       = false;
static volatile bool     notif_clear_pending = false;
static volatile bool     gps_raw_pending     = false;
static twister_turn_t    pending_turn        = {};
static twister_notif_t   pending_notif       = {};
static twister_gps_raw_t pending_gps_raw     = {};

static void print_hex(const uint8_t *data, size_t len)
{
    for (size_t i = 0; i < len; ++i) {
        if (data[i] < 0x10) {
            Serial.print('0');
        }
        Serial.print(data[i], HEX);
        Serial.print(' ');
    }
    Serial.println();
}

static size_t build_demo_turn_frame(uint8_t *out, size_t out_max)
{
    const char *road = "Av Siempre Viva";
    const uint8_t road_len = 15;
    const uint8_t payload_len = (uint8_t)(5 + road_len);
    const size_t frame_len = (size_t)(3 + payload_len + 1);

    if (out_max < frame_len) {
        return 0;
    }

    out[0] = TWISTER_MSG_TURN;
    out[1] = 1;
    out[2] = payload_len;
    out[3] = TWISTER_DIR_RIGHT;
    out[4] = 120 & 0xff;
    out[5] = (120 >> 8) & 0xff;
    out[6] = 0;  // exit_number = 0
    out[7] = road_len;
    for (uint8_t i = 0; i < road_len; ++i) {
        out[8 + i] = (uint8_t)road[i];
    }

    out[frame_len - 1] = twister_crc8_xor(out, frame_len - 1);
    return frame_len;
}

// ---------- helpers de pantalla ----------

// --- Layout: pantalla 320x172, landscape ---
// Barra de estado: y=0..17  (18px)
// Panel izquierdo (notif, 60%): x=0..191,   y=18..171  — 192px, 15 chars/linea @sz2
// Divisor:                      x=192..193,  y=18..171
// Panel derecho  (GPS,  40%):   x=194..319,  y=18..171  — 126px,  9 chars/linea @sz2
#define NOTIF_X  0
#define NOTIF_W  192     // 60% de 320px
#define DIV_X    192
#define GPS_X    194
#define GPS_W    126     // 40% de 320px - 2px divisor
#define BAR_H    18
#define CONTENT_Y BAR_H
#define CONTENT_H (172 - BAR_H)   // 154 px

#define FONT_SZ_BAR    1   // barra de estado  (1: 6x8 px/char)
#define FONT_SZ_NOTIF  3   // panel notificaciones izquierdo (2: 12x16 px/char)
#define FONT_SZ_GPS    3   // panel GPS derecho              (2: 12x16 px/char)

// ═══════════════════════════════════════════════════════════════
//  PALETA DE COLORES  (RGB565 — equivalente HTML para referencia)
// ═══════════════════════════════════════════════════════════════

// ── UI global ─────────────────────────────────────────────────
#define COL_FONDO_PANTALLA            0x0000   // #000000  negro puro       — fondo de ambos paneles
#define COL_BARRA_FONDO_CONECTADO     0x0320   // #006100  verde muy oscuro  — barra estado: BLE activo
#define COL_BARRA_FONDO_DESCONECTADO  0xA000   // #C00000  rojo muy oscuro   — barra estado: BLE inactivo
#define COL_BARRA_TEXTO               0xFFFF   // #FFFFFF  blanco            — texto en la barra de estado
#define COL_DIVISOR_PANELES           0x4208   // #404040  gris oscuro       — linea vertical entre paneles
#define COL_ETIQUETA_INACTIVA         0x8410   // #808080  gris medio        — textos apagados ("GPS", "Notificaciones", dots)

// ── Zona izquierda — Notificaciones ───────────────────────────
#define COL_NOTIF_APP_WHATSAPP        0x07E0   // #00FF00  verde vivo        — etiqueta app WhatsApp
#define COL_NOTIF_APP_TELEGRAM        0x07FF   // #00FFFF  cian              — etiqueta app Telegram
#define COL_NOTIF_APP_LLAMADA         0xF81F   // #FF00FF  fucsia/magenta    — etiqueta llamada entrante
#define COL_NOTIF_APP_OTRO            0xFFFF   // #FFFFFF  blanco            — etiqueta app generica
#define COL_NOTIF_TITULO              0xA7E0   // #A0FF00  verde lima        — primera linea: titulo/remitente
#define COL_NOTIF_CUERPO              0xFFFF   // #FFFFFF  blanco puro       — segunda linea: cuerpo del mensaje

// ── Zona derecha — GPS / Navegacion ───────────────────────────
#define COL_GPS_ETIQUETA              0x8410   // #808080  gris medio        — texto dim "GPS" en cabecera panel
#define COL_GPS_DIRECCION             0xFD20   // #FFA000  naranja ambar     — instruccion de giro (DER>>, IZQ<<...)
#define COL_GPS_SEPARADOR_H           0x4208   // #404040  gris oscuro       — linea horizontal interna del panel
#define COL_GPS_DISTANCIA             0xFFFF   // #FFFFFF  blanco puro       — numero de distancia (120m, 1.2km)
#define COL_GPS_CALLE                 0x07FF   // #00FFFF  cian              — nombre de la calle o via
// Semaforo de proximidad al giro (flecha de navegacion)
#define COL_GPS_LEJOS                 0xFFFF   // #FFFFFF  blanco            — >100m: lejos, sin urgencia
#define COL_GPS_CERCA                 0xFD20   // #FFA000  ambar naranja     — 51-100m: preparate
#define COL_GPS_INMEDIATO             0xA7E0   // #A0FF00  verde lima        — <=50m: gira ahora

// --- Convierte UTF-8 (Latin extendido) a ASCII plano quitando diacriticos ---
// Las fuentes GFX proporcionales solo tienen glifos ASCII 32-126; cp437 no aplica.
// tft.cp437(true) solo afecta a la fuente built-in (flechas GPS).
static void utf8_to_ascii(const char *src, char *dst, size_t dst_max)
{
    const uint8_t *s = (const uint8_t *)src;
    uint8_t *d = (uint8_t *)dst;
    size_t rem = dst_max - 1;
    while (*s && rem > 0) {
        if (*s < 0x80) {
            *d++ = *s++; rem--;
        } else if (s[0] == 0xC2 && s[1]) {
            uint8_t lo = s[1], c = '?';
            if      (lo == 0xA1) c = '!';   // ¡
            else if (lo == 0xBF) c = '?';   // ¿
            *d++ = c; s += 2; rem--;
        } else if (s[0] == 0xC3 && s[1]) {
            uint8_t lo = s[1], c = '?';
            switch (lo) {
                case 0x80: case 0x81: case 0x82: case 0x83:
                case 0x84: case 0x85: c = 'A'; break;  // À Á Â Ã Ä Å
                case 0x86: c = 'A'; break;              // Æ
                case 0x87: c = 'C'; break;              // Ç
                case 0x88: case 0x89: case 0x8A: case 0x8B: c = 'E'; break;  // È É Ê Ë
                case 0x8C: case 0x8D: case 0x8E: case 0x8F: c = 'I'; break;  // Ì Í Î Ï
                case 0x90: c = 'D'; break;              // Ð
                case 0x91: c = 'N'; break;              // Ñ
                case 0x92: case 0x93: case 0x94: case 0x95:
                case 0x96: c = 'O'; break;              // Ò Ó Ô Õ Ö
                case 0x98: c = 'O'; break;              // Ø
                case 0x99: case 0x9A: case 0x9B: case 0x9C: c = 'U'; break;  // Ù Ú Û Ü
                case 0x9D: c = 'Y'; break;              // Ý
                case 0xA0: case 0xA1: case 0xA2: case 0xA3:
                case 0xA4: case 0xA5: c = 'a'; break;  // à á â ã ä å
                case 0xA6: c = 'a'; break;              // æ
                case 0xA7: c = 'c'; break;              // ç
                case 0xA8: case 0xA9: case 0xAA: case 0xAB: c = 'e'; break;  // è é ê ë
                case 0xAC: case 0xAD: case 0xAE: case 0xAF: c = 'i'; break;  // ì í î ï
                case 0xB0: c = 'd'; break;              // ð
                case 0xB1: c = 'n'; break;              // ñ
                case 0xB2: case 0xB3: case 0xB4: case 0xB5:
                case 0xB6: c = 'o'; break;              // ò ó ô õ ö
                case 0xB8: c = 'o'; break;              // ø
                case 0xB9: case 0xBA: case 0xBB: case 0xBC: c = 'u'; break;  // ù ú û ü
                case 0xBD: case 0xBF: c = 'y'; break;  // ý ÿ
                default:   c = '?'; break;
            }
            *d++ = c; s += 2; rem--;
        } else {
            s++;
            while ((*s & 0xC0) == 0x80) s++;  // saltar bytes de continuacion
        }
    }
    *d = '\0';
}

// --- Imprime texto con ajuste de linea dentro de un recuadro (x0,y0,bw,bh) ---
// Para fuentes built-in: cy es esquina superior izquierda.
// Para fuentes proporcionales: cy es la primera baseline (= y0 + ascent).
static void printBounded(int16_t x0, int16_t y0, int16_t bw, int16_t bh,
                         uint8_t sz, const char *str)
{
    tft.setTextWrap(false);

    // Detectar si hay fuente proporcional activa midiendo 'A'
    int16_t tx, ty; uint16_t tw, th;
    tft.getTextBounds("A", 0, 100, &tx, &ty, &tw, &th);
    bool builtin = (tw == 6u * sz);

    if (builtin) {
        const int16_t cw = 6 * sz;
        const int16_t ch = 8 * sz;
        int16_t cx = x0, cy = y0;
        const char *p = str;
        while (*p) {
            if (cy + ch > y0 + bh) break;
            if (*p == '\n') { cx = x0; cy += ch + 1; p++; continue; }
            if (cx + cw > x0 + bw) { cx = x0; cy += ch + 1; if (*p == ' ') { p++; continue; } }
            if (cy + ch > y0 + bh) break;
            tft.setCursor(cx, cy);
            tft.write((uint8_t)*p++);
            cx += cw;
        }
        return;
    }

    // Fuente proporcional: word-wrap por palabras
    const int16_t line_h = (int16_t)th + 3;  // avance de linea
    // Ancho del espacio: medir "X X" - "XX" para obtener el advance real
    int16_t sx, sy; uint16_t sw, sh;
    int16_t sx2, sy2; uint16_t sw2, sh2;
    tft.getTextBounds("X X", 0, 100, &sx,  &sy,  &sw,  &sh);
    tft.getTextBounds("XX",  0, 100, &sx2, &sy2, &sw2, &sh2);
    const int16_t space_w = ((int16_t)sw > (int16_t)sw2) ? ((int16_t)sw - (int16_t)sw2) : 4;

    int16_t cx = x0;
    int16_t cy = y0 + (int16_t)th;  // primera baseline
    const char *p = str;

    while (*p && cy <= y0 + bh) {
        if (*p == '\n') { cx = x0; cy += line_h; p++; continue; }
        if (*p == ' ')  { cx += space_w; p++; continue; }

        // extraer palabra
        const char *wstart = p;
        while (*p && *p != ' ' && *p != '\n') p++;
        char word[64];
        size_t wlen = (size_t)(p - wstart);
        if (wlen >= sizeof(word)) wlen = sizeof(word) - 1;
        memcpy(word, wstart, wlen);
        word[wlen] = '\0';

        // medir palabra
        int16_t wx, wy; uint16_t ww, wh;
        tft.getTextBounds(word, cx, cy, &wx, &wy, &ww, &wh);

        // salto de linea si no cabe
        if (cx > x0 && cx + (int16_t)ww > x0 + bw) {
            cx = x0; cy += line_h;
            tft.getTextBounds(word, cx, cy, &wx, &wy, &ww, &wh);
        }
        if (cy > y0 + bh) break;
        tft.setCursor(cx, cy);
        tft.print(word);
        cx += (int16_t)ww;
    }
}

// --- Etiqueta de direccion GPS (texto corto, para Serial debug) ---
static const char *dir_gps_label(twister_turn_direction_t d)
{
    switch (d) {
        case TWISTER_DIR_LEFT:       return "IZQ";
        case TWISTER_DIR_RIGHT:      return "DER";
        case TWISTER_DIR_UTURN:      return "GIRO U";
        case TWISTER_DIR_EXIT_LEFT:  return "SAL IZQ";
        case TWISTER_DIR_EXIT_RIGHT: return "SAL DER";
        case TWISTER_DIR_ROUNDABOUT: return "ROTONDA";
        default:                     return "RECTO";
    }
}

// --- Icono CP437 para la direccion (fuente built-in) ---
static uint8_t dir_gps_arrow(twister_turn_direction_t d)
{
    switch (d) {
        case TWISTER_DIR_LEFT:       return 0x1B;  // ←
        case TWISTER_DIR_RIGHT:      return 0x1A;  // →
        case TWISTER_DIR_UTURN:      return 0x19;  // ↓
        case TWISTER_DIR_EXIT_LEFT:  return 0x1B;  // ←
        case TWISTER_DIR_EXIT_RIGHT: return 0x1A;  // →
        case TWISTER_DIR_ROUNDABOUT: return 0x4F;  // 'O' placeholder (no se usa en render)
        default:                     return 0x18;  // ↑
    }
}

static const char *notif_source_label(twister_notif_source_t src)
{
    switch (src) {
        case TWISTER_NOTIF_CALL:     return "LLAMADA";
        case TWISTER_NOTIF_WHATSAPP: return "WhatsApp";
        case TWISTER_NOTIF_TELEGRAM: return "Telegram";
        case TWISTER_NOTIF_SYSTEM:   return "Fecha/Hora";
        default:                     return "NOTIF";
    }
}

static uint16_t notif_source_color(twister_notif_source_t src)
{
    switch (src) {
        case TWISTER_NOTIF_CALL:     return COL_NOTIF_APP_LLAMADA;
        case TWISTER_NOTIF_WHATSAPP: return COL_NOTIF_APP_WHATSAPP;
        case TWISTER_NOTIF_TELEGRAM: return COL_NOTIF_APP_TELEGRAM;
        case TWISTER_NOTIF_SYSTEM:   return COL_BARRA_TEXTO;
        default:                     return COL_NOTIF_APP_OTRO;
    }
}

// --- Barra de estado (fila superior, ancho total) ---
static void display_status_bar(bool connected)
{
#if TWISTER_HAS_ST7789
    tft.fillRect(0, 0, 320, BAR_H, connected ? COL_BARRA_FONDO_CONECTADO : COL_BARRA_FONDO_DESCONECTADO);
    tft.setTextColor(COL_BARRA_TEXTO);
    tft.setTextSize(FONT_SZ_BAR);
    tft.setCursor(4, 5);
    tft.print(connected ? "BLE conectado   Twister" : "BLE desconectado...");
#endif
}

// --- Divisor vertical entre paneles ---
static void display_divider()
{
#if TWISTER_HAS_ST7789
    tft.fillRect(DIV_X, CONTENT_Y, 2, CONTENT_H, COL_DIVISOR_PANELES);
#endif
}

// --- Panel derecho en reposo (sin GPS) ---
static void display_gps_idle()
{
#if TWISTER_HAS_ST7789
    tft.fillRect(GPS_X, CONTENT_Y, GPS_W, CONTENT_H, COL_FONDO_PANTALLA);
    tft.setFont(&FreeSans12pt7b);
    tft.setTextSize(1);
    tft.setTextColor(COL_ETIQUETA_INACTIVA);
    tft.setCursor(GPS_X + 4, CONTENT_Y + 26);
    tft.print("Sin senal");
    tft.setFont(NULL);
#endif
}

// --- Panel izquierdo en reposo (sin notif) ---
static void display_notif_idle()
{
#if TWISTER_HAS_ST7789
    if (!system_time_received) {
        tft.fillRect(NOTIF_X, CONTENT_Y, NOTIF_W, CONTENT_H, COL_FONDO_PANTALLA);
        tft.setFont(&FreeSans12pt7b);
        tft.setTextSize(1);
        tft.setTextColor(COL_ETIQUETA_INACTIVA);
        tft.setCursor(NOTIF_X + 10, CONTENT_Y + 45);
        tft.print("Esperando");
        tft.setCursor(NOTIF_X + 10, CONTENT_Y + 75);
        tft.print("conexion...");
        tft.setFont(NULL);
        last_drawn_date[0] = '\0';
        last_drawn_time[0] = '\0';
        return;
    }

    bool date_changed = (strcmp(last_drawn_date, system_date) != 0);
    bool time_changed = (strcmp(last_drawn_time, system_time) != 0);

    if (date_changed || time_changed) {
        tft.fillRect(NOTIF_X, CONTENT_Y, NOTIF_W, CONTENT_H, COL_FONDO_PANTALLA);
        
        tft.setFont(NULL);
        tft.setTextSize(1);
        tft.setTextColor(COL_ETIQUETA_INACTIVA);
        tft.setCursor(NOTIF_X + 8, CONTENT_Y + 10);
        tft.print("RELOJ");
        tft.drawFastHLine(NOTIF_X + 4, CONTENT_Y + 22, NOTIF_W - 8, COL_DIVISOR_PANELES);

        // Dibujar la Fecha (YYYY-MM-DD)
        // Centrado: ancho de 10 caracteres en FreeSans12pt7b es ~110px. (192-110)/2 = 41px.
        tft.setFont(&FreeSans12pt7b);
        tft.setTextSize(1);
        tft.setTextColor(COL_NOTIF_TITULO); // Verde lima
        tft.setCursor(NOTIF_X + 41, CONTENT_Y + 54);
        tft.print(system_date);

        // Dibujar la Hora (HH:mm)
        // Centrado: ancho de 5 caracteres en FreeSans18pt7b es ~80px. (192-80)/2 = 56px.
        tft.setFont(&FreeSans18pt7b);
        tft.setTextSize(1);
        tft.setTextColor(COL_BARRA_TEXTO); // Blanco
        tft.setCursor(NOTIF_X + 56, CONTENT_Y + 115);
        tft.print(system_time);

        tft.setFont(NULL);

        strcpy(last_drawn_date, system_date);
        strcpy(last_drawn_time, system_time);
    }
#endif
}

// --- Muestra pagina idx de la cola de notificaciones ---
static void display_notif_page(uint8_t idx)
{
#if TWISTER_HAS_ST7789
    if (notif_queue_count == 0) { display_notif_idle(); return; }

    char title_buf[TWISTER_MAX_NOTIF_TEXT + 2];
    char body_buf[TWISTER_MAX_NOTIF_TEXT + 2];
    char combined[TWISTER_MAX_NOTIF_TEXT * 2 + 4];
    const twister_notif_t &notif = notif_queue[idx];

    tft.fillRect(NOTIF_X, CONTENT_Y, NOTIF_W, CONTENT_H, COL_FONDO_PANTALLA);

    // Fila 1: app label (fuente proporcional, baseline en y+20)
    tft.setFont(&FreeSans12pt7b);
    tft.setTextSize(1);
    tft.setTextColor(notif_source_color(notif.source));
    tft.setCursor(NOTIF_X + 4, CONTENT_Y + 20);
    tft.print(notif_source_label(notif.source));

    // Indicador n/total (fuente built-in, gris, alineado a la derecha)
    if (notif_queue_count > 1) {
        char pag[8];
        snprintf(pag, sizeof(pag), "%d/%d", idx + 1, notif_queue_count);
        tft.setFont(NULL);
        tft.setTextSize(FONT_SZ_BAR);
        tft.setTextColor(COL_ETIQUETA_INACTIVA);
        tft.setCursor(NOTIF_X + NOTIF_W - (int16_t)strlen(pag) * 6 - 4, CONTENT_Y + 7);
        tft.print(pag);
    }

    // Separador
    tft.drawFastHLine(NOTIF_X + 4, CONTENT_Y + 27, NOTIF_W - 8, COL_DIVISOR_PANELES);

    // Titulo: cuerpo — bloque unico
    utf8_to_ascii(notif.title, title_buf, sizeof(title_buf));
    utf8_to_ascii(notif.body,  body_buf,  sizeof(body_buf));
    snprintf(combined, sizeof(combined), "%s: %s", title_buf, body_buf);
    tft.setFont(&FreeSans12pt7b);
    tft.setTextSize(1);
    tft.setTextColor(COL_NOTIF_CUERPO);
    printBounded(NOTIF_X + 4, CONTENT_Y + 34, NOTIF_W - 8, CONTENT_H - 37, 1, combined);
    tft.setFont(NULL);
#endif
}

// --- Transicion de pagina: barrido ambar hacia abajo (~80ms) ---
static void notif_page_transition()
{
#if TWISTER_HAS_ST7789
    for (int16_t y = CONTENT_Y; y < CONTENT_Y + CONTENT_H; y += 4) {
        tft.drawFastHLine(NOTIF_X, y, NOTIF_W, COL_GPS_DIRECCION);
        if (y >= CONTENT_Y + 8)
            tft.drawFastHLine(NOTIF_X, y - 8, NOTIF_W, COL_FONDO_PANTALLA);
        delay(2);
    }
    tft.fillRect(NOTIF_X, CONTENT_Y, NOTIF_W, CONTENT_H, COL_FONDO_PANTALLA);
#endif
}

// --- Inserta nueva notificacion al frente del buffer (mas reciente = indice 0) ---
static void push_notif(const twister_notif_t &n)
{
    if (notif_queue_count < NOTIF_QUEUE_SIZE) {
        notif_queue_count++;
    }
    for (int8_t i = (int8_t)notif_queue_count - 1; i > 0; --i) {
        notif_queue[i] = notif_queue[i - 1];
    }
    notif_queue[0] = n;
    notif_display_idx  = 0;
    notif_last_page_ms = millis();
    display_notif_page(0);
}

// --- Muestra texto crudo de navegacion en el panel GPS (sin flecha ni distancia) ---
static void display_gps_raw(const twister_gps_raw_t &raw)
{
#if TWISTER_HAS_ST7789
    char buf[TWISTER_MAX_GPS_RAW + 2];
    utf8_to_ascii(raw.text, buf, sizeof(buf));
    tft.fillRect(GPS_X, CONTENT_Y, GPS_W, CONTENT_H, COL_FONDO_PANTALLA);
    tft.setFont(&FreeSans12pt7b);
    tft.setTextSize(1);
    tft.setTextColor(COL_GPS_DISTANCIA);
    printBounded(GPS_X + 4, CONTENT_Y + 4, GPS_W - 8, CONTENT_H - 8, 1, buf);
    tft.setFont(NULL);
#endif
}

// Umbrales de proximidad al giro (semaforo de color):
//   dist > TURN_FAR_M  : flecha blanca     — lejos, sin urgencia
//   dist <= TURN_FAR_M : flecha ambar      — preparate
//   dist <= TURN_IMM_M : flecha roja       — gira ahora
#define TURN_FAR_M  100
#define TURN_IMM_M   50

// --- Muestra instruccion GPS en el panel derecho ---
static void display_turn(const twister_turn_t &turn)
{
#if TWISTER_HAS_ST7789
    Serial.printf("Turn: dir=%u dist=%um road=%s\n", (unsigned)turn.direction, turn.distance_m, turn.road_name);
    char buf[TWISTER_MAX_ROAD_NAME + 2];

    tft.fillRect(GPS_X, CONTENT_Y, GPS_W, CONTENT_H, COL_FONDO_PANTALLA);

    // Flecha de direccion — sz6 (36x48px) para giros normales.
    // Rotonda: "R" + digito de salida sz5 (30x40px c/u, 60px total centrados).
    // Semaforo de proximidad (siempre muestra la maniobra real):
    //   > 100 m : blanco     — lejos, sin urgencia
    //  51-100 m : ambar      — preparate para girar
    //   <= 50 m : verde lima — gira ahora
    const uint8_t arrow_char = dir_gps_arrow(turn.direction);
    uint16_t arrow_color;
    if (turn.distance_m <= TURN_IMM_M) {
        arrow_color = COL_GPS_INMEDIATO;   // verde lima — gira ahora
    } else if (turn.distance_m <= TURN_FAR_M) {
        arrow_color = COL_GPS_CERCA;       // ambar      — preparate
    } else {
        arrow_color = COL_GPS_LEJOS;       // blanco     — lejos
    }
    tft.setFont(NULL);
    tft.setTextColor(arrow_color);
    if (turn.direction == TWISTER_DIR_ROUNDABOUT) {
        // "R" + digito de salida (sz5: 30x40px c/u, 60px total centrados)
        const int16_t rx = GPS_X + (GPS_W - 60) / 2;
        tft.setTextSize(5);
        tft.setCursor(rx, CONTENT_Y + 4);
        tft.write('R');
        tft.setCursor(rx + 30, CONTENT_Y + 4);
        tft.write(turn.exit_number > 0 && turn.exit_number <= 9
                  ? (uint8_t)('0' + turn.exit_number)
                  : (uint8_t)'?');
    } else {
        tft.setTextSize(6);                          // sz6: 36x48px
        tft.setCursor(GPS_X + (GPS_W - 36) / 2, CONTENT_Y + 4);
        tft.write(arrow_char);
    }

    // Separador 1 — justo bajo la flecha (top+4 + 48px alto + 4px gap = y+56)
    tft.drawFastHLine(GPS_X + 4, CONTENT_Y + 56, GPS_W - 8, COL_GPS_SEPARADOR_H);

    // Nombre de la calle — zona (~60px, ~3 lineas)
    utf8_to_ascii(turn.road_name, buf, sizeof(buf));
    tft.setFont(&FreeSans12pt7b);
    tft.setTextSize(1);
    tft.setTextColor(COL_GPS_CALLE);
    printBounded(GPS_X + 4, CONTENT_Y + 62, GPS_W - 8, 60, 1, buf);

    // Separador 2 — justo encima de la distancia al fondo (calle bottom = y+62+60=y+122)
    tft.drawFastHLine(GPS_X + 4, CONTENT_Y + 124, GPS_W - 8, COL_GPS_SEPARADOR_H);

    // Distancia — anclada al fondo del panel (baseline y+150)
    tft.setFont(&FreeSans18pt7b);
    tft.setTextSize(1);
    tft.setTextColor(COL_GPS_DISTANCIA);
    tft.setCursor(GPS_X + 4, CONTENT_Y + 150);
    if (turn.distance_m >= 1000) {
        tft.print(turn.distance_m / 1000);
        tft.print('.');
        tft.print((turn.distance_m % 1000) / 100);
        tft.print("km");
    } else {
        tft.print(turn.distance_m);
        tft.print('m');
    }
    tft.setFont(NULL);
#endif
}

// ---------- animacion de arranque ----------

static void play_splash_animation()
{
#if TWISTER_HAS_ST7789
    tft.fillScreen(COL_FONDO_PANTALLA);

    // ── Fase 1: scan ambar de arriba a abajo con cola de borrado ──
    for (int16_t y = 0; y < 172; y += 3) {
        tft.drawFastHLine(0, y, 320, COL_GPS_DIRECCION);
        if (y >= 12) tft.drawFastHLine(0, y - 12, 320, COL_FONDO_PANTALLA);
        delay(6);
    }
    tft.fillScreen(COL_FONDO_PANTALLA);

    // ── Fase 2: "TWISTER" letra a letra ──
    // textSize=4 -> 24x32 px/char;  7 chars = 168 px -> cx = (320-168)/2 = 76
    tft.setTextSize(4);
    tft.setTextColor(COL_BARRA_TEXTO);
    const int16_t tx = 76;
    const int16_t ty = (172 - 32) / 2 - 8;  // 62
    const char *title = "TWISTER";
    for (uint8_t i = 0; title[i]; i++) {
        tft.setCursor(tx + (int16_t)i * 24, ty);
        tft.write((uint8_t)title[i]);
        delay(50);
    }

    // ── Tagline ──
    delay(150);
    tft.setTextSize(1);
    tft.setTextColor(COL_ETIQUETA_INACTIVA);
    // "Motorcycle HUD" = 14 chars * 6 = 84 px -> cx = (320-84)/2 = 118
    tft.setCursor(118, ty + 40);
    tft.print("Motorcycle HUD");
    delay(700);

    // ── Fase 3: cortina izq->der con filo cian ──
    for (int16_t x = 0; x < 320; x += 6) {
        tft.fillRect(x, 0, 6, 172, COL_FONDO_PANTALLA);
        if (x + 6 < 320)
            tft.drawFastVLine(x + 6, 0, 172, COL_GPS_CALLE);
        delay(4);
    }
    tft.fillRect(0, 0, 320, 172, COL_FONDO_PANTALLA);

    // ── Pantalla idle normal ──
    display_status_bar(ble_connected);
    display_divider();
    display_notif_idle();
    display_gps_idle();
    layout_ready = true;
#endif
}

// ---------- BLE callbacks ----------

class TwisterServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer *pServer) override
    {
        ble_connected = true;
        Serial.println("BLE conectado");
        display_status_bar(true);
    }

    void onDisconnect(BLEServer *pServer) override
    {
        ble_connected = false;
        Serial.println("BLE desconectado, reiniciando advertising");
        display_status_bar(false);
        BLEDevice::startAdvertising();
    }
};

class TwisterRxCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) override
    {
        String val = pChar->getValue();
        if (val.length() == 0) return;
        const uint8_t *data = (const uint8_t *)val.c_str();
        size_t len = val.length();

        twister_message_t msg = {};
        if (!twister_parse_frame(data, len, &msg)) {
            Serial.println("BLE RX: frame invalido");
            return;
        }
        Serial.print("BLE RX type=0x");
        Serial.println(msg.type, HEX);

        switch (msg.type) {
            case TWISTER_MSG_TURN:
                // Diferir al loop() para evitar race condition SPI con la tarea BLE
                memcpy(&pending_turn, &msg.data.turn, sizeof(pending_turn));
                turn_pending = true;
                break;
            case TWISTER_MSG_NOTIF:
                memcpy(&pending_notif, &msg.data.notif, sizeof(pending_notif));
                notif_pending = true;
                break;
            case TWISTER_MSG_GPS_RAW:
                memcpy(&pending_gps_raw, &msg.data.gps_raw, sizeof(pending_gps_raw));
                gps_raw_pending = true;
                break;
            case TWISTER_MSG_CMD:
                if (msg.data.cmd_id == TWISTER_CMD_SPLASH)
                    splash_requested = true;
                else if (msg.data.cmd_id == TWISTER_CMD_CLEAR_NOTIF)
                    notif_clear_pending = true;
                break;
            default:
                break;
        }
    }
};

static void init_ble()
{
    BLEDevice::init(BLE_DEVICE_NAME);

    // Bonding persistente: claves guardadas en NVS; reconexion automatica sin re-emparejar
    BLESecurity *pSec = new BLESecurity();
    pSec->setAuthenticationMode(ESP_LE_AUTH_BOND);       // bond sin MITM (Just Works)
    pSec->setCapability(ESP_IO_CAP_NONE);                // sin teclado ni pantalla => Just Works
    pSec->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
    pSec->setRespEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);

    BLEServer *pServer = BLEDevice::createServer();
    pServer->setCallbacks(new TwisterServerCallbacks());

    BLEService *pService = pServer->createService(BLE_SVC_UUID);

    BLECharacteristic *pRxChar = pService->createCharacteristic(
        BLE_CHAR_RX_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
    );
    pRxChar->setCallbacks(new TwisterRxCallbacks());

    pTxChar = pService->createCharacteristic(
        BLE_CHAR_TX_UUID,
        BLECharacteristic::PROPERTY_NOTIFY
    );
    pTxChar->addDescriptor(new BLE2902());

    pService->start();

    BLEAdvertising *pAdv = BLEDevice::getAdvertising();
    pAdv->addServiceUUID(BLE_SVC_UUID);
    pAdv->setAppearance(0x0000);   // Generic Unknown — evita dialogo Samsung "app necesaria"
    pAdv->setScanResponse(true);   // necesario para que el nombre completo "Twister" sea visible
    BLEDevice::startAdvertising();
    Serial.println("BLE advertising: " BLE_DEVICE_NAME);
}

// ---------- display init ----------

static void init_display()
{
#if TWISTER_HAS_ST7789
    SPI.begin(LCD_PIN_SCLK, -1, LCD_PIN_MOSI, LCD_PIN_CS);

    pinMode(LCD_PIN_BL, OUTPUT);
    digitalWrite(LCD_PIN_BL, HIGH);

    tft.setSPISpeed(10000000);
    tft.init(172, 320, SPI_MODE0);
    tft.setRotation(1);  // landscape: 320x172
    tft.invertDisplay(true);  // ST7789 Spotpear: colores invertidos por hardware
    tft.cp437(true);           // interpretar bytes > 0x7F como CP437 (acentos, flechas)

    tft.fillScreen(COL_FONDO_PANTALLA);
    display_status_bar(false);
    tft.setTextColor(COL_BARRA_TEXTO);
    tft.setTextSize(2);
    tft.setCursor(10, 40);
    tft.println("Twister");
    tft.setTextSize(2);
    tft.setCursor(10, 75);
    tft.println("Esperando BLE...");
    Serial.println("Display OK");
#endif
}

static void update_display_heartbeat(uint32_t seconds)
{
#if TWISTER_HAS_ST7789
    if (!ble_connected && !layout_ready) {
        // Animar el punto suspensivo de espera
        static uint8_t dots = 0;
        tft.fillRect(10, 75, 200, 17, COL_FONDO_PANTALLA);
        tft.setCursor(10, 75);
        tft.setTextColor(COL_ETIQUETA_INACTIVA);
        tft.setTextSize(FONT_SZ_NOTIF);
        tft.print("Esperando BLE");
        for (uint8_t i = 0; i <= (dots % 3); ++i) tft.print('.');
        dots++;
    }
#else
    (void)seconds;
#endif
}

void setup()
{
    Serial.begin(115200);
    delay(1500);
    Serial.println("Twister Arduino bootstrap");
    init_display();
    play_splash_animation();
    init_ble();
}

void loop()
{
    if (splash_requested) {
        splash_requested = false;
        play_splash_animation();
    }

    // Procesar actualizaciones pendientes de la tarea BLE (SPI solo desde loop)
    if (turn_pending) {
        turn_pending = false;
        display_turn(pending_turn);
    }
    if (gps_raw_pending) {
        gps_raw_pending = false;
        display_gps_raw(pending_gps_raw);
    }
    if (notif_pending) {
        notif_pending = false;
        if (pending_notif.source == TWISTER_NOTIF_SYSTEM) {
            strncpy(system_date, pending_notif.title, sizeof(system_date) - 1);
            system_date[sizeof(system_date) - 1] = '\0';
            strncpy(system_time, pending_notif.body, sizeof(system_time) - 1);
            system_time[sizeof(system_time) - 1] = '\0';

            // Parsear para el reloj local (HH:mm)
            int hh = 0, mm = 0;
            if (sscanf(system_time, "%d:%d", &hh, &mm) >= 2) {
                system_time_seconds = hh * 3600 + mm * 60;
            }
            system_time_received = true;
            last_time_tick_ms = millis();

            if (notif_queue_count == 0) {
                display_notif_idle();
            }
        } else {
            push_notif(pending_notif);
        }
    }
    if (notif_clear_pending) {
        notif_clear_pending = false;
        notif_queue_count = 0;
        notif_display_idx = 0;
        last_drawn_date[0] = '\0';
        last_drawn_time[0] = '\0';
        display_notif_idle();
    }

    // Incrementar el reloj local por software cada segundo y redibujar si cambia el minuto
    if (system_time_received && (millis() - last_time_tick_ms >= 1000)) {
        unsigned long elapsed = (millis() - last_time_tick_ms) / 1000;
        last_time_tick_ms += elapsed * 1000;

        uint32_t prev_seconds = system_time_seconds;
        system_time_seconds = (system_time_seconds + elapsed) % 86400;

        // Si cambiaron los minutos, formatear y actualizar pantalla
        if ((system_time_seconds / 60) != (prev_seconds / 60)) {
            int hh = system_time_seconds / 3600;
            int mm = (system_time_seconds % 3600) / 60;
            snprintf(system_time, sizeof(system_time), "%02d:%02d", hh, mm);

            if (notif_queue_count == 0) {
                display_notif_idle();
            }
        }
    }

    // Auto-scroll de notificaciones: de mas reciente a mas antigua
    if (notif_queue_count > 1 &&
        (millis() - notif_last_page_ms >= NOTIF_SCROLL_MS)) {
        notif_display_idx  = (uint8_t)((notif_display_idx + 1) % notif_queue_count);
        notif_last_page_ms = millis();
        notif_page_transition();
        display_notif_page(notif_display_idx);
    }

    static unsigned long last_ms = 0;
    if (millis() - last_ms >= 1500) {
        last_ms = millis();
        update_display_heartbeat(millis() / 1000UL);
    }
}
