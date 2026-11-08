// Test fixture ONLY: an interface with the same name as Peloton's, compiled by the real AIDL tool,
// so the bike check's code scanner is verified against genuine AIDL output on a real Android runtime.
package com.onepeloton.affernetservice;

interface IBikeInterface {
    int getBikeData();
    void setTargetResistance(int resistance);
    void startCalibration();
}
