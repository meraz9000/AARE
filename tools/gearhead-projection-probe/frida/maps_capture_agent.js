const Java = require('frida-java-bridge').default;

Java.perform(function () {
    // net.prototux.aare (the app under development) and net.prototux.probe (the standalone
    // protocol probe). UIDs are resolved at runtime too, because reinstalling an app can change
    // them; these are only the values seen on the test device.
    var TARGET_UIDS = [10358, 10360];
    var TARGET_PACKAGES = ['net.prototux.aare', 'net.prototux.probe'];
    var PERMISSION = 'android.permission.CAPTURE_VIDEO_OUTPUT';
    var Binder = Java.use('android.os.Binder');
    var hits = 0;

    function learnUids() {
        try {
            var app = Java.use('android.app.ActivityThread').currentApplication();
            if (!app) return;
            var pm = app.getPackageManager();
            TARGET_PACKAGES.forEach(function (pkg) {
                try {
                    var uid = pm.getPackageUid(pkg, 0);
                    if (TARGET_UIDS.indexOf(uid) < 0) {
                        TARGET_UIDS.push(uid);
                        console.log('[hook] ' + pkg + ' is uid=' + uid);
                    }
                } catch (e) {
                }
            });
        } catch (e) {
        }
    }
    learnUids();
    setTimeout(learnUids, 1000);
    setInterval(learnUids, 5000);

    function shouldAllow(permission) {
        if (permission !== PERMISSION) return false;
        var uid = Binder.getCallingUid();
        if (TARGET_UIDS.indexOf(uid) >= 0) {
            hits++;
            console.log('[hook] grant ' + permission + ' to uid=' + uid +
                ' (call ' + hits + ')');
            return true;
        }
        console.log('[hook] deny ' + permission + ' to uid=' + uid);
        return false;
    }

    function hookCheck(clsName) {
        try {
            var cls = Java.use(clsName);
            var original = cls.checkCallingPermission.overload('java.lang.String');
            original.implementation = function (permission) {
                if (shouldAllow(permission)) return 0;
                return original.call(this, permission);
            };
            console.log('[hook] ' + clsName + '.checkCallingPermission installed');
        } catch (e) {
            console.log('[hook] ' + clsName + ' hook failed: ' + e);
        }
    }
    hookCheck('android.app.ContextImpl');
    hookCheck('android.content.ContextWrapper');

    console.log('[hook] ready');
});
