const Java = require('frida-java-bridge').default;

Java.perform(function () {
    var TARGET_UID = 10358; // net.prototux.probe
    var PERMISSION = 'android.permission.CAPTURE_VIDEO_OUTPUT';
    var Binder = Java.use('android.os.Binder');
    var hits = 0;

    function shouldAllow(permission) {
        if (permission !== PERMISSION) return false;
        var uid = Binder.getCallingUid();
        if (uid === TARGET_UID) {
            hits++;
            console.log('[hook] grant ' + permission + ' to uid=' + uid +
                ' (call ' + hits + ')');
            return true;
        }
        console.log('[hook] deny ' + permission + ' to uid=' + uid);
        return false;
    }

    try {
        var ContextImpl = Java.use('android.app.ContextImpl');
        ContextImpl.checkCallingPermission.overload('java.lang.String').implementation = function (permission) {
            if (shouldAllow(permission)) return 0;
            return this.checkCallingPermission(permission);
        };
        console.log('[hook] ContextImpl.checkCallingPermission installed');
    } catch (e) {
        console.log('[hook] ContextImpl hook failed: ' + e);
    }

    try {
        var ContextWrapper = Java.use('android.content.ContextWrapper');
        ContextWrapper.checkCallingPermission.overload('java.lang.String').implementation = function (permission) {
            if (shouldAllow(permission)) return 0;
            return this.checkCallingPermission(permission);
        };
        console.log('[hook] ContextWrapper.checkCallingPermission installed');
    } catch (e) {
        console.log('[hook] ContextWrapper hook failed: ' + e);
    }

    console.log('[hook] ready');
});
