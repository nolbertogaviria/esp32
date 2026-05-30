# Firmware ESP32-C6

Base del firmware con ESP-IDF para Proyecto Twister.

## Modulos iniciales

- Parser de protocolo TLV v1.
- Modelo de eventos (TURN, NOTIF, HEARTBEAT).
- Builders de frame para STATUS y HEARTBEAT (TX).
- Bucle principal de ejemplo para futura integracion BLE + ST7789.

## Build en Windows 11 (VS Code)

1. Instalar extension ESP-IDF en VS Code y configurar ESP-IDF 5.x.
2. Abrir `firmware/esp32-c6`.
3. Seleccionar target `esp32c6`.
4. Ejecutar Build desde la extension ESP-IDF.

## Nota

La capa BLE GATT y el driver de pantalla quedan en la siguiente iteracion.
