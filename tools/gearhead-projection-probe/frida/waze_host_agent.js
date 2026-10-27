// Waze declares a public Car App Library service, but its HostValidator only allows
// Google's Android Auto host. This development hook accepts AARE's host so the template
// UI can be exercised on a rooted test phone. It does not modify the Waze APK.
const Java = require('frida-java-bridge').default;

Java.perform(function () {
    const AARE = 'net.prototux.aare';
    try {
        const HostValidator = Java.use('androidx.car.app.validation.HostValidator');
        const isValidHost = HostValidator.isValidHost.overload('androidx.car.app.HostInfo');
        const original = isValidHost.implementation;
        isValidHost.implementation = function (hostInfo) {
            let pkg = '';
            let uid = -1;
            try {
                pkg = hostInfo.getPackageName();
                uid = hostInfo.getUid();
            } catch (e) {
            }
            if (pkg === AARE) {
                console.log('[waze-hook] allow ' + pkg + ' uid=' + uid);
                return true;
            }
            return original.call(this, hostInfo);
        };
        console.log('[waze-hook] HostValidator.isValidHost installed for ' + AARE);
    } catch (e) {
        console.log('[waze-hook] install failed: ' + e);
    }
});
