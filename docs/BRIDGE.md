# BikeOS Bridge: control the bike from your own Android app

Apps installed **on the bike tablet** can't use the Bluetooth smart-trainer broadcast, because Android can't connect to its own advertisement. The BikeOS Bridge is the on-device equivalent: plain Android broadcasts, protected by permissions.

## Permissions

```xml
<!-- Read live metrics (normal permission, granted at install) -->
<uses-permission android:name="com.bikeos.permission.READ_METRICS" />
<!-- Control the bike (dangerous permission: the rider approves it in Android's permission prompt) -->
<uses-permission android:name="com.bikeos.permission.CONTROL_BIKE" />
```

Request `CONTROL_BIKE` at runtime like any other dangerous permission. The rider can also switch the whole Bridge off in **BikeOS → Settings → Apps controlling the bike**.

## Read live metrics (1 Hz)

```kotlin
val receiver = object : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val power = i.getIntExtra("power", 0)          // W
        val cadence = i.getIntExtra("cadence", 0)      // rpm
        val resistance = i.getIntExtra("resistance", 0) // 0-100
        val hr = i.getIntExtra("heartRate", -1)         // bpm, -1 if no strap
        val riding = i.getBooleanExtra("riding", false)
        // also: paused, speedMph (Float), elapsedSec, outputKj, calories, distanceMiles (Double),
        //       targetResistance (Float, -1 if none), controlledBy (String?)
    }
}
// Register dynamically (Android 8+ doesn't deliver implicit broadcasts to manifest receivers).
registerReceiver(receiver, IntentFilter("com.bikeos.bridge.METRICS"))
```

## Control the bike

```kotlin
fun bike(command: String, value: Double? = null) = sendBroadcast(
    Intent("com.bikeos.bridge.CONTROL")
        .setPackage("com.bikeos")
        .putExtra("command", command)
        .apply { value?.let { putExtra("value", it) } }
        .putExtra("app", "My Training App"), // shown to the rider as "Controlled by …"
)

bike("power", 220.0)      // ERG: hold 220 W (0-2000)
bike("resistance", 45.0)  // resistance 0-100
bike("grade", 4.5)        // virtual hill, % (-25 to 25), mapped by the rider's "hill feel" settings
bike("clear")             // hand control back to the rider / class
bike("start"); bike("pause"); bike("resume"); bike("end")
```

Out-of-range values are rejected, not clamped. Who controls the bike, highest priority first:
1. Bluetooth trainer apps (Zwift…)
2. Bridge apps
3. The rider's auto-pilot
4. Class targets

On a **Peloton Bike+** with auto-resistance enabled, targets drive the resistance motor through BikeOS's safety limiter:
- the rider's ceiling
- at most 10 points per step, one step per second
- never harder while the rider isn't pedaling

On a **Peloton Bike** (manual knob), targets appear as "turn up / turn down" coaching in the overlay.
