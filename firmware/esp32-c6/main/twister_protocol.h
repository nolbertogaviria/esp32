#ifndef TWISTER_PROTOCOL_H
#define TWISTER_PROTOCOL_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define TWISTER_MAX_ROAD_NAME 40
#define TWISTER_MAX_NOTIF_TEXT 64

typedef enum {
    TWISTER_MSG_TURN = 0x01,
    TWISTER_MSG_NOTIF = 0x02,
    TWISTER_MSG_HEARTBEAT = 0x03,
    TWISTER_MSG_STATUS = 0x04,
} twister_msg_type_t;

typedef enum {
    TWISTER_DIR_STRAIGHT = 0,
    TWISTER_DIR_LEFT = 1,
    TWISTER_DIR_RIGHT = 2,
    TWISTER_DIR_UTURN = 3,
    TWISTER_DIR_EXIT_LEFT = 4,
    TWISTER_DIR_EXIT_RIGHT = 5,
} twister_turn_direction_t;

typedef enum {
    TWISTER_NOTIF_CALL = 0,
    TWISTER_NOTIF_WHATSAPP = 1,
    TWISTER_NOTIF_TELEGRAM = 2,
} twister_notif_source_t;

typedef struct {
    twister_turn_direction_t direction;
    uint16_t distance_m;
    char road_name[TWISTER_MAX_ROAD_NAME + 1];
} twister_turn_t;

typedef struct {
    twister_notif_source_t source;
    char title[33];
    char body[TWISTER_MAX_NOTIF_TEXT + 1];
} twister_notif_t;

typedef struct {
    uint32_t uptime_s;
    uint8_t flags;
} twister_heartbeat_t;

typedef struct {
    twister_msg_type_t type;
    uint8_t seq;
    union {
        twister_turn_t turn;
        twister_notif_t notif;
        twister_heartbeat_t heartbeat;
    } data;
} twister_message_t;

bool twister_parse_frame(const uint8_t *frame, size_t frame_len, twister_message_t *out_msg);
uint8_t twister_crc8_xor(const uint8_t *data, size_t len);

size_t twister_build_heartbeat_frame(uint8_t seq,
                                     uint32_t uptime_s,
                                     uint8_t flags,
                                     uint8_t *out,
                                     size_t out_max);

size_t twister_build_status_frame(uint8_t seq,
                                  uint8_t rssi_percent,
                                  uint8_t gps_fix,
                                  uint8_t *out,
                                  size_t out_max);

#endif
