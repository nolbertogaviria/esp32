#ifndef TWISTER_PROTOCOL_H
#define TWISTER_PROTOCOL_H

#include <Arduino.h>

#define TWISTER_MAX_ROAD_NAME 40
#define TWISTER_MAX_NOTIF_TEXT 64

enum twister_msg_type_t {
    TWISTER_MSG_TURN = 0x01,
    TWISTER_MSG_NOTIF = 0x02,
    TWISTER_MSG_HEARTBEAT = 0x03,
    TWISTER_MSG_STATUS = 0x04,
    TWISTER_MSG_CMD = 0x05,   // comando de control (splash, etc.)
    TWISTER_MSG_GPS_RAW = 0x06,  // texto crudo de navegacion (sin parsear)
};

#define TWISTER_MAX_GPS_RAW 80   // max bytes de texto crudo GPS

#define TWISTER_CMD_SPLASH       0x01   // reproducir animacion de arranque
#define TWISTER_CMD_CLEAR_NOTIF  0x02   // limpiar cola de notificaciones

enum twister_turn_direction_t {
    TWISTER_DIR_STRAIGHT = 0,
    TWISTER_DIR_LEFT = 1,
    TWISTER_DIR_RIGHT = 2,
    TWISTER_DIR_UTURN = 3,
    TWISTER_DIR_EXIT_LEFT = 4,
    TWISTER_DIR_EXIT_RIGHT = 5,
    TWISTER_DIR_ROUNDABOUT = 6,   // rotonda — usar exit_number para número de salida
};

enum twister_notif_source_t {
    TWISTER_NOTIF_CALL = 0,
    TWISTER_NOTIF_WHATSAPP = 1,
    TWISTER_NOTIF_TELEGRAM = 2,
    TWISTER_NOTIF_SYSTEM = 3,  // notificacion del sistema / app Twister
};

struct twister_gps_raw_t {
    char text[TWISTER_MAX_GPS_RAW + 1];
};

struct twister_turn_t {
    twister_turn_direction_t direction;
    uint16_t distance_m;
    uint8_t  exit_number;    // rotonda: número de salida (1-9); 0 si no aplica
    char road_name[TWISTER_MAX_ROAD_NAME + 1];
};

struct twister_notif_t {
    twister_notif_source_t source;
    char title[33];
    char body[TWISTER_MAX_NOTIF_TEXT + 1];
};

struct twister_heartbeat_t {
    uint32_t uptime_s;
    uint8_t flags;
};

struct twister_message_t {
    twister_msg_type_t type;
    uint8_t seq;
    union {
        twister_turn_t turn;
        twister_notif_t notif;
        twister_heartbeat_t heartbeat;
        twister_gps_raw_t gps_raw;
        uint8_t cmd_id;   // para TWISTER_MSG_CMD
    } data;
};

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
