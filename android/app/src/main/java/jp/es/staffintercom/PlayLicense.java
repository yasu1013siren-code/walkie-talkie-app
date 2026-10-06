package jp.es.staffintercom;

import android.content.Context;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.StandardIntegrityManager;

/** Tokens live only until server admission; no local entitlement or offline bypass. */
final class PlayLicense {
    interface Callback { void success(String token); void failure(); }
    static void request(Context context, long project, String hash, Callback callback) {
        if (project <= 0 || !hash.matches("[A-Za-z0-9_-]{43}")) { callback.failure(); return; }
        IntegrityManagerFactory.createStandard(context.getApplicationContext()).prepareIntegrityToken(
            StandardIntegrityManager.PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(project).build())
            .addOnSuccessListener(provider -> provider.request(
                StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(hash).build())
                .addOnSuccessListener(response -> callback.success(response.token()))
                .addOnFailureListener(error -> callback.failure()))
            .addOnFailureListener(error -> callback.failure());
    }
}
