#include <inttypes.h>

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "twister_protocol.h"

static const char *TAG = "twister";

static void log_message(const twister_message_t *msg)
{
    switch (msg->type) {
        case TWISTER_MSG_TURN:
            ESP_LOGI(TAG,
                     "TURN seq=%u dir=%u dist=%u road=%s",
                     msg->seq,
                     (unsigned)msg->data.turn.direction,
                     (unsigned)msg->data.turn.distance_m,
                     msg->data.turn.road_name);
            break;
        case TWISTER_MSG_NOTIF:
            ESP_LOGI(TAG,
                     "NOTIF seq=%u src=%u title=%s body=%s",
                     msg->seq,
                     (unsigned)msg->data.notif.source,
                     msg->data.notif.title,
                     msg->data.notif.body);
            break;
        case TWISTER_MSG_HEARTBEAT:
            ESP_LOGI(TAG,
                     "HEARTBEAT seq=%u uptime=%" PRIu32 " flags=0x%02x",
                     msg->seq,
                     msg->data.heartbeat.uptime_s,
                     msg->data.heartbeat.flags);
            break;
        default:
            ESP_LOGW(TAG, "Mensaje no soportado: %u", (unsigned)msg->type);
            break;
    }
}

static size_t build_demo_turn_frame(uint8_t *out, size_t out_max)
{
    const char *road = "Av Siempre Viva";
    const uint8_t road_len = 15;
    const uint8_t payload_len = (uint8_t)(4 + road_len);
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
    out[6] = road_len;
    for (uint8_t i = 0; i < road_len; ++i) {
        out[7 + i] = (uint8_t)road[i];
    }

    out[frame_len - 1] = twister_crc8_xor(out, frame_len - 1);
    return frame_len;
}

void app_main(void)
{
    ESP_LOGI(TAG, "Twister firmware bootstrap iniciado");

    uint8_t frame[64] = {0};
    const size_t frame_len = build_demo_turn_frame(frame, sizeof(frame));

    twister_message_t msg = {0};
    if (frame_len > 0 && twister_parse_frame(frame, frame_len, &msg)) {
        log_message(&msg);
    } else {
        ESP_LOGE(TAG, "No se pudo parsear frame de demo");
    }

    uint8_t tx_frame[16] = {0};
    size_t tx_len = twister_build_status_frame(2, 85, 1, tx_frame, sizeof(tx_frame));
    if (tx_len > 0) {
        ESP_LOGI(TAG, "STATUS frame listo para BLE TX (len=%u)", (unsigned)tx_len);
    }

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(2000));
        ESP_LOGI(TAG, "Loop vivo, listo para integrar BLE + ST7789");
    }
}
