#include "twister_protocol.h"

#include <string.h>

static uint16_t read_u16_le(const uint8_t *p)
{
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

uint8_t twister_crc8_xor(const uint8_t *data, size_t len)
{
    uint8_t crc = 0;
    for (size_t i = 0; i < len; ++i) {
        crc ^= data[i];
    }
    return crc;
}

static bool parse_gps_raw(const uint8_t *payload, size_t len, twister_gps_raw_t *out)
{
    if (len > TWISTER_MAX_GPS_RAW) len = TWISTER_MAX_GPS_RAW;
    memcpy(out->text, payload, len);
    out->text[len] = '\0';
    return true;
}

static bool parse_turn(const uint8_t *payload, size_t len, twister_turn_t *turn)
{
    if (len < 5) {
        return false;
    }

    uint8_t road_len = payload[4];
    if (road_len > TWISTER_MAX_ROAD_NAME || (size_t)(5 + road_len) != len) {
        return false;
    }

    turn->direction   = (twister_turn_direction_t)payload[0];
    turn->distance_m  = read_u16_le(&payload[1]);
    turn->exit_number = payload[3];
    memcpy(turn->road_name, &payload[5], road_len);
    turn->road_name[road_len] = '\0';
    return true;
}

static bool copy_text_field(const uint8_t *src, size_t src_len, char *dst, size_t dst_max)
{
    if (src_len >= dst_max) {
        return false;
    }

    memcpy(dst, src, src_len);
    dst[src_len] = '\0';
    return true;
}

static bool parse_notif(const uint8_t *payload, size_t len, twister_notif_t *notif)
{
    if (len < 3) {
        return false;
    }

    size_t idx = 0;
    notif->source = (twister_notif_source_t)payload[idx++];

    uint8_t title_len = payload[idx++];
    if ((idx + title_len + 1) > len) {
        return false;
    }
    if (!copy_text_field(&payload[idx], title_len, notif->title, sizeof(notif->title))) {
        return false;
    }
    idx += title_len;

    uint8_t body_len = payload[idx++];
    if ((idx + body_len) != len) {
        return false;
    }
    if (!copy_text_field(&payload[idx], body_len, notif->body, sizeof(notif->body))) {
        return false;
    }

    return true;
}

static bool parse_heartbeat(const uint8_t *payload, size_t len, twister_heartbeat_t *heartbeat)
{
    if (len != 5) {
        return false;
    }

    heartbeat->uptime_s =
        (uint32_t)payload[0] |
        ((uint32_t)payload[1] << 8) |
        ((uint32_t)payload[2] << 16) |
        ((uint32_t)payload[3] << 24);
    heartbeat->flags = payload[4];
    return true;
}

bool twister_parse_frame(const uint8_t *frame, size_t frame_len, twister_message_t *out_msg)
{
    if (!frame || !out_msg || frame_len < 4) {
        return false;
    }

    const uint8_t type = frame[0];
    const uint8_t seq = frame[1];
    const uint8_t len = frame[2];

    if (frame_len != (size_t)(3 + len + 1)) {
        return false;
    }

    const uint8_t crc = frame[frame_len - 1];
    if (twister_crc8_xor(frame, frame_len - 1) != crc) {
        return false;
    }

    out_msg->type = (twister_msg_type_t)type;
    out_msg->seq = seq;
    const uint8_t *payload = &frame[3];

    switch (out_msg->type) {
        case TWISTER_MSG_TURN:
            return parse_turn(payload, len, &out_msg->data.turn);
        case TWISTER_MSG_NOTIF:
            return parse_notif(payload, len, &out_msg->data.notif);
        case TWISTER_MSG_HEARTBEAT:
            return parse_heartbeat(payload, len, &out_msg->data.heartbeat);
        case TWISTER_MSG_GPS_RAW:
            return parse_gps_raw(payload, len, &out_msg->data.gps_raw);
        case TWISTER_MSG_CMD:
            if (len < 1) return false;
            out_msg->data.cmd_id = payload[0];
            return true;
        default:
            return false;
    }
}

size_t twister_build_heartbeat_frame(uint8_t seq,
                                     uint32_t uptime_s,
                                     uint8_t flags,
                                     uint8_t *out,
                                     size_t out_max)
{
    const uint8_t payload_len = 5;
    const size_t frame_len = (size_t)(3 + payload_len + 1);
    if (!out || out_max < frame_len) {
        return 0;
    }

    out[0] = TWISTER_MSG_HEARTBEAT;
    out[1] = seq;
    out[2] = payload_len;
    out[3] = (uint8_t)(uptime_s & 0xff);
    out[4] = (uint8_t)((uptime_s >> 8) & 0xff);
    out[5] = (uint8_t)((uptime_s >> 16) & 0xff);
    out[6] = (uint8_t)((uptime_s >> 24) & 0xff);
    out[7] = flags;
    out[8] = twister_crc8_xor(out, frame_len - 1);
    return frame_len;
}

size_t twister_build_status_frame(uint8_t seq,
                                  uint8_t rssi_percent,
                                  uint8_t gps_fix,
                                  uint8_t *out,
                                  size_t out_max)
{
    const uint8_t payload_len = 3;
    const size_t frame_len = (size_t)(3 + payload_len + 1);
    if (!out || out_max < frame_len) {
        return 0;
    }

    out[0] = TWISTER_MSG_STATUS;
    out[1] = seq;
    out[2] = payload_len;
    out[3] = rssi_percent;
    out[4] = gps_fix;
    out[5] = 0;
    out[6] = twister_crc8_xor(out, frame_len - 1);
    return frame_len;
}
