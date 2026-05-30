# Twister BLE Protocol v1

Este protocolo define el intercambio de mensajes entre Android y ESP32-C6.

## Transporte

- BLE GATT custom service.
- Android escribe en characteristic RX del ESP32.
- ESP32 notifica estado por characteristic TX.

## Encapsulado TLV

Formato base de frame:

1. type (1 byte)
2. seq (1 byte)
3. len (1 byte)
4. payload (len bytes)
5. crc8 (1 byte)

CRC8: XOR simple de todos los bytes anteriores del frame (MVP).

## Tipos de mensaje

- 0x01 TURN
- 0x02 NOTIF
- 0x03 HEARTBEAT
- 0x04 STATUS

## TURN payload

1. direction (1 byte):
   - 0 straight
   - 1 left
   - 2 right
   - 3 uturn
   - 4 exit_left
   - 5 exit_right
2. distance_m (2 bytes, uint16 LE)
3. road_name_len (1 byte)
4. road_name (ASCII/UTF-8 corto, max 40 bytes)

## NOTIF payload

1. source (1 byte):
   - 0 call
   - 1 whatsapp
   - 2 telegram
2. title_len (1 byte)
3. title (max 32)
4. body_len (1 byte)
5. body (max 64)

## HEARTBEAT payload

1. uptime_s (4 bytes, uint32 LE)
2. flags (1 byte)

## STATUS payload

1. rssi_percent (1 byte)
2. gps_fix (1 byte)
3. reserved (1 byte)

## Compatibilidad

- seq permite deduplicar mensajes al reconectar.
- len y CRC permiten validar integridad.
- En v2 se puede migrar a CBOR manteniendo type reservado para compat.
