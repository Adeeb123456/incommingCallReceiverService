package playmzubair.com.incommingcallreceiverservice;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.support.v4.content.ContextCompat;

/**
 * Created by adeeb on 10/13/2018.
 */

public class Permissions {
    public static final String[] CONTACTS = new String[]{
            Manifest.permission.READ_CONTACTS,
    };

    public static boolean permitted(Context context, String[] ss) {
        boolean permittedForce=false;
        if (permittedForce)
            return true;
        if (Build.VERSION.SDK_INT < 16)
            return true;
        for (String s : ss) {
            if (ContextCompat.checkSelfPermission(context, s) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
