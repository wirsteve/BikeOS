// Test fixture ONLY (see IBikeInterface.aidl).
package com.onepeloton.affernetservice;

interface IV1Interface {
    void registerCallback(IBinder callback, String name);
    void unregisterCallback(IBinder callback, String name);
}
