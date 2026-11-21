# URAN TASHTRI — Protocol Specification

## Architecture

Android -> BLE -> COMM ESP32-S3 ->
UART 9600 baud -> Flight ESP32-S3

All text packets use newline framing.

## BLE device

URAN_TASHTRI_COMM

Service UUID:
6E400001-B5A3-F393-E0A9-E50E24DCCA9E

Phone-write UUID:
6E400002-B5A3-F393-E0A9-E50E24DCCA9E

Phone-notify UUID:
6E400003-B5A3-F393-E0A9-E50E24DCCA9E

## Joystick control

C,<seq>,M,<roll>,<pitch>,<yaw>,<throttle>

seq: 0..255
roll/pitch/yaw: -20..+20
throttle: 0 in BENCH_ONLY

Example:
C,42,M,10,0,-5,0

Nominal interval: 100 ms.

COMM acknowledgement:
ACK,COMM_RC,42

COMM receipt is not proof that Flight
acted on the command.

## Safety commands

DISARM
ESTOP

These take highest priority in the BLE
write queue.

ESTOP locks the application.

A UI reset requires confirmed Flight
E-STOP clearance after a verified latch.

## Preparation commands

DISARM
SIM_OFF
LEVEL
STATUS

Sequence:
DISARM -> SIM_OFF -> LEVEL -> STATUS

Suggested response requirements:

DISARM:
ACK,DISARMED

SIM_OFF:
ACK,SIM_OFF
or ACK,SIMULATION_OFF

LEVEL:
ACK,LEVEL
or ACK,LEVEL_OK
or ACK,LEVEL_CALIBRATED

STATUS:
Fresh STATE,MPU=... packet

These exact responses must be verified
against COMM and Flight firmware.

Unsupported commands must fail visibly.

Each preparation step has a timeout
(default 4500 ms).

A disconnect, error or E-STOP cancels
the entire preparation.

## Flight state telemetry

Legacy:
STATE,MPU=OK,LEVEL=1,LINK=1,
ESTOP=0,SIM=0,BENCH=0,KEY=1,
ESC_ENABLED=1,MODE=BENCH_ONLY

The above is one UART line.

Transient events such as
STATE,BENCH_STOPPED must not overwrite
the last full state snapshot.

## Newer telemetry

ST,<sim>,<mpu>,<cal>,<estop>,<disarmed>,<rc>

AT,<roll_d10>,<pitch_d10>,<gyroz_d10>

MO,<FL>,<FR>,<RR>,<RL>

CS,<uart>,<pilot>

ST and CS flags use 0 or 1.

Motor values are virtual percentages
in the current simulation protocol.

## Battery telemetry

BAT,<millivolts>,<milliamps>,
<remaining_percent>,<valid>

Example:
BAT,11820,-1,-1,1

This means:
Pack voltage = 11.820V
Current = unavailable
Percentage = unavailable
Reading valid = true

Android estimates battery percentage
from voltage approximately.

The voltage sensor cannot measure
individual LiPo cell voltages.

## Telemetry stale thresholds

Flight state: 3000 ms
Attitude: 1500 ms
Motor: 1500 ms
COMM status: 3000 ms
Battery: 3000 ms

Stale telemetry must never prove
flight readiness.

## Readiness states

IDLE
PREPARING
BENCH_PREPARED
FAILED
STALE

BENCH_PREPARED does not authorize
real flight.

Real FLIGHT_READY is reserved for
future firmware capability support.

## Future firmware commands

PREFLIGHT
ARM
MOTOR_TEST

Currently disabled.

Do not send or enable physical throttle
until proper flight firmware, arming,
stabilization and failsafes exist.

## Current bench safeguards

Physical key
USB bench authorization
Neutral stick requirements
Fixed motor-test duration
DISARM
ESTOP
Motor stop acknowledgement

Android must not bypass these.

## Firmware changes required

COMM must forward:
STATUS
LEVEL
SIM_OFF

COMM must forward corresponding Flight
acknowledgements to Android BLE.

Flight must send fresh state telemetry.

Future Flight firmware must implement
independent RC-loss, low-voltage,
stabilization and arming safeguards.
