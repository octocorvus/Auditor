package app.attestation.auditor;

import android.Manifest;
import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Html;
import android.text.Spanned;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.snackbar.Snackbar;
import com.google.common.collect.ImmutableSet;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.EnumMap;
import java.util.Map;
import java.util.zip.DataFormatException;

import static android.graphics.Color.BLACK;
import static android.graphics.Color.WHITE;

public class AttestationActivity extends AppCompatActivity {
    private static final String TAG = "AttestationActivity";

    private static final String TUTORIAL_URL = "https://attestation.app/tutorial";

    private static final String STATE_AUDITEE_PAIRING = "auditee_pairing";
    private static final String STATE_AUDITEE_SERIALIZED_ATTESTATION = "auditee_serialized_attestation";
    private static final String STATE_AUDITOR_CHALLENGE = "auditor_challenge";
    private static final String STATE_STAGE = "stage";
    private static final String STATE_OUTPUT = "output";
    private static final String STATE_BACKGROUND_RESOURCE = "background_resource";

    private static final int PERMISSIONS_REQUEST_CAMERA = 0;
    private static final int PERMISSIONS_REQUEST_POST_NOTIFICATIONS_REMOTE_VERIFY = 1;
    private static final int PERMISSIONS_REQUEST_POST_NOTIFICATIONS_SUBMIT_SAMPLE = 2;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView textView;
    private ImageView imageView;
    private View buttons;
    private Snackbar snackbar;

    private enum Stage {
        None,
        Auditee,
        AuditeeGenerate,
        AuditeeResults,
        Auditor,
        Result, // Auditor success/failure and Auditee failure
        EnableRemoteVerify
    }

    private Stage stage = Stage.None;
    private boolean auditeePairing;
    private byte[] auditeeSerializedAttestation;
    private byte[] auditorChallenge;
    private int backgroundResource;
    private boolean canSubmitSample;

    final ActivityResultLauncher<Intent> QRScannerActivityLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent intent = result.getData();
                    if (intent != null) {
                        final String contents = intent.getStringExtra(QRScannerActivity.EXTRA_SCAN_RESULT);
                        if (contents == null) {
                            if (stage == Stage.Auditee) {
                                stage = Stage.None;
                            }
                            return;
                        }
                        final byte[] contentsBytes;
                        contentsBytes = contents.getBytes(StandardCharsets.ISO_8859_1);
                        if (stage == Stage.Auditee) {
                            stage = Stage.AuditeeGenerate;
                            buttons.setVisibility(View.GONE);
                            generateAttestation(contentsBytes);
                        } else if (stage == Stage.Auditor) {
                            stage = Stage.Result;
                            imageView.setVisibility(View.GONE);
                            handleAttestation(contentsBytes);
                        } else if (stage == Stage.EnableRemoteVerify) {
                            stage = Stage.None;
                            Log.d(TAG, "account: " + contents);
                            final String[] values = contents.split(" ");
                            if (values.length < 4 || !RemoteVerifyJob.DOMAIN.equals(values[0])) {
                                snackbar.setText(R.string.scanned_invalid_account_qr_code).show();
                                return;
                            }
                            PreferenceManager.getDefaultSharedPreferences(this).edit()
                                    .putLong(RemoteVerifyJob.KEY_USER_ID, Long.parseLong(values[1]))
                                    .putString(RemoteVerifyJob.KEY_SUBSCRIBE_KEY, values[2])
                                    .apply();
                            try {
                                RemoteVerifyJob.schedule(this, Integer.parseInt(values[3]));
                                snackbar.setText(R.string.enable_remote_verify_success).show();
                            } catch (final NumberFormatException e) {
                                snackbar.setText(R.string.scanned_invalid_account_qr_code).show();
                            }
                        } else {
                            throw new RuntimeException("received unexpected scan result");
                        }
                    } else {
                        if (stage == Stage.Auditee) {
                            stage = Stage.None;
                        }
                    }
                }
            });

    private static final boolean isSupportedAuditee = ImmutableSet.of(
            "ALP-L29",
            "AUM-L29",
            "Aquaris X2 Pro",
            "BBF100-1",
            "BBF100-6",
            "BKL-L04",
            "BKL-L09",
            "CLT-L29",
            "COL-L29",
            "DUB-LX3",
            "CPH1831",
            "CPH1903",
            "CPH1909",
            "EML-L09",
            "EXODUS 1",
            "G8341",
            "G8342",
            "G8441",
            "GM1913",
            "H3113",
            "H3123",
            "H4113",
            "H8216",
            "H8314",
            "H8324",
            "HTC 2Q55100",
            "JKM-LX3",
            "LLD-L31",
            "LG-Q710AL",
            "LM-Q720",
            "LYA-L29",
            "Mi A2",
            "Mi A2 Lite",
            "MI 9",
            "moto g(7)",
            "motorola one vision",
            "Nokia 3.1",
            "Nokia 6.1",
            "Nokia 6.1 Plus",
            "Nokia 7.1",
            "Nokia 7 plus",
            "ONEPLUS A6003",
            "ONEPLUS A6013",
            "Pixel 2",
            "Pixel 2 XL",
            "Pixel 3",
            "Pixel 3 XL",
            "Pixel 3a",
            "Pixel 3a XL",
            "Pixel 4",
            "Pixel 4 XL",
            "Pixel 4a",
            "Pixel 4a (5G)",
            "Pixel 5",
            "Pixel 5a",
            "Pixel 6",
            "Pixel 6 Pro",
            "Pixel 6a",
            "Pixel 7",
            "Pixel 7 Pro",
            "POCOPHONE F1",
            "POT-LX3",
            "REVVL 2",
            "RMX1941",
            "SM-A705FN",
            "SM-G960F",
            "SM-G960U",
            "SM-G960U1",
            "SM-G960W",
            "SM-G9600",
            "SM-G965F",
            "SM-G965U",
            "SM-G965U1",
            "SM-G965W",
            "SM-G970F",
            "SM-G975F",
            "SM-J260A",
            "SM-J260F",
            "SM-J260T1",
            "SM-J337A",
            "SM-J337AZ",
            "SM-J337T",
            "SM-J720F",
            "SM-J737T1",
            "SM-M205F",
            "SM-N960F",
            "SM-N960U",
            "SM-N970F",
            "SM-N970U",
            "SM-N975U",
            "SM-S367VL",
            "SM-T510",
            "SM-T835",
            "SNE-LX1",
            "vivo 1807").contains(Build.MODEL);

    private static int getFirstApiLevel() {
        return Integer.parseInt(SystemProperties.get("ro.product.first_api_level",
                Integer.toString(Build.VERSION.SDK_INT)));
    }

    private static boolean potentialSupportedAuditee() {
        return getFirstApiLevel() >= Build.VERSION_CODES.O;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_attestation);
        setSupportActionBar(findViewById(R.id.toolbar));

        buttons = findViewById(R.id.buttons);
        snackbar = Snackbar.make(findViewById(R.id.content_attestation), "", Snackbar.LENGTH_LONG);

        findViewById(R.id.auditee).setOnClickListener((final View view) -> {
            if (!isSupportedAuditee) {
                snackbar.setText(R.string.unsupported_auditee).show();
                return;
            }
            stage = Stage.Auditee;
            startQrScanner();
        });

        findViewById(R.id.auditor).setOnClickListener(view -> {
            snackbar.dismiss();
            stage = Stage.Auditor;
            buttons.setVisibility(View.GONE);
            runAuditor();
        });

        textView = findViewById(R.id.textview);

        imageView = findViewById(R.id.imageview);

        if (savedInstanceState != null) {
            auditeePairing = savedInstanceState.getBoolean(STATE_AUDITEE_PAIRING);
            auditeeSerializedAttestation = savedInstanceState.getByteArray(STATE_AUDITEE_SERIALIZED_ATTESTATION);
            auditorChallenge = savedInstanceState.getByteArray(STATE_AUDITOR_CHALLENGE);
            stage = Stage.valueOf(savedInstanceState.getString(STATE_STAGE));
            textView.setText(Html.fromHtml(savedInstanceState.getString(STATE_OUTPUT),
                    Html.FROM_HTML_MODE_LEGACY));
            backgroundResource = savedInstanceState.getInt(STATE_BACKGROUND_RESOURCE);
        }

        final ViewTreeObserver vto = imageView.getViewTreeObserver();
        vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                imageView.getViewTreeObserver().removeOnPreDrawListener(this);
                if (stage != Stage.None) {
                    buttons.setVisibility(View.GONE);
                    if (stage == Stage.AuditeeResults) {
                        auditeeShowAttestation(auditeeSerializedAttestation);
                    } else if (stage == Stage.Auditor) {
                        runAuditor();
                    }
                }
                findViewById(R.id.content_attestation).setBackgroundResource(backgroundResource);
                return true;
            }
        });

        RemoteVerifyJob.restore(this);
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putBoolean(STATE_AUDITEE_PAIRING, auditeePairing);
        savedInstanceState.putByteArray(STATE_AUDITEE_SERIALIZED_ATTESTATION, auditeeSerializedAttestation);
        savedInstanceState.putByteArray(STATE_AUDITOR_CHALLENGE, auditorChallenge);
        savedInstanceState.putString(STATE_STAGE, stage.name());
        savedInstanceState.putString(STATE_OUTPUT, Html.toHtml((Spanned) textView.getText(),
                Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE));
        savedInstanceState.putInt(STATE_BACKGROUND_RESOURCE, backgroundResource);
    }

    private void chooseBestLayout(final byte[] data) {
        final View content = findViewById(R.id.content_attestation);
        final LinearLayout resultLayout = findViewById(R.id.result);

        final ViewTreeObserver vto = content.getViewTreeObserver();
        vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                content.getViewTreeObserver().removeOnPreDrawListener(this);
                if (content.getHeight() - textView.getHeight() >
                        content.getWidth() - textView.getWidth()) {
                    resultLayout.setOrientation(LinearLayout.VERTICAL);

                    final ViewTreeObserver vto = imageView.getViewTreeObserver();
                    vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                        @Override
                        public boolean onPreDraw() {
                            imageView.getViewTreeObserver().removeOnPreDrawListener(this);
                            imageView.setImageBitmap(createQrCode(data));
                            return true;
                        }
                    });
                } else {
                    imageView.setImageBitmap(createQrCode(data));
                }
                return true;
            }
        });
    }

    private void runAuditor() {
        if (auditorChallenge == null) {
            auditorChallenge = AttestationProtocol.getChallengeMessage(this);
        }
        Log.d(TAG, "sending random challenge: " + Utils.logFormatBytes(auditorChallenge));
        textView.setText(R.string.qr_code_scan_hint_auditor);
        chooseBestLayout(auditorChallenge);
        imageView.setOnClickListener(view -> startQrScanner());
    }

    private void handleAttestation(final byte[] serialized) {
        Log.d(TAG, "received attestation: " + Utils.logFormatBytes(serialized));
        textView.setText(R.string.verifying_attestation);
        executor.submit(() -> {
            try {
                final AttestationProtocol.VerificationResult result = AttestationProtocol.verifySerialized(this, serialized, auditorChallenge);
                runOnUiThread(() -> {
                    setBackgroundResource(result.strong ? R.color.green : R.color.orange);
                    textView.setText(result.strong ? R.string.verify_strong : R.string.verify_basic);
                    textView.append(getText(R.string.hardware_enforced));
                    textView.append(result.teeEnforced);
                    textView.append(getText(R.string.os_enforced));
                    textView.append(result.osEnforced);
                    if (!result.history.isEmpty()) {
                        textView.append(getText(R.string.history));
                        textView.append(result.history);
                    }
                });
            } catch (final DataFormatException | GeneralSecurityException | IOException e) {
                Log.e(TAG, "attestation verification error", e);
                runOnUiThread(() -> {
                    setBackgroundResource(R.color.red);
                    textView.setText(R.string.verify_error);
                    textView.append(e.getMessage());
                });
            } catch (final BufferUnderflowException | NegativeArraySizeException e) {
                Log.e(TAG, "attestation verification error", e);
                runOnUiThread(() -> {
                    setBackgroundResource(R.color.red);
                    textView.setText(R.string.verify_error);
                    textView.append(e.getMessage());
                });
            }
        });
    }

    private void generateAttestation(final byte[] challenge) {
        Log.d(TAG, "received random challenge: " + Utils.logFormatBytes(challenge));
        textView.setText(R.string.generating_attestation);
        executor.submit(() -> {
            try {
                final AttestationProtocol.AttestationResult result =
                        AttestationProtocol.generateSerialized(this, challenge, null, "");
                runOnUiThread(() -> {
                    auditeePairing = result.pairing;
                    auditeeShowAttestation(result.serialized);
                });
            } catch (final GeneralSecurityException | IOException e) {
                Log.e(TAG, "attestation generation error", e);
                runOnUiThread(() -> {
                    stage = Stage.Result;
                    setBackgroundResource(R.color.red);
                    textView.setText(R.string.generate_error);
                    textView.append(e.getMessage());
                });
            }
        });
    }

    private void auditeeShowAttestation(final byte[] serialized) {
        Log.d(TAG, "sending attestation: " + Utils.logFormatBytes(serialized));
        auditeeSerializedAttestation = serialized;
        stage = Stage.AuditeeResults;
        if (auditeePairing) {
            textView.setText(R.string.qr_code_scan_hint_auditee_pairing);
        } else {
            textView.setText(R.string.qr_code_scan_hint_auditee);
        }
        chooseBestLayout(serialized);
    }

    private Bitmap createQrCode(final byte[] contents) {
        final BitMatrix result;
        try {
            final QRCodeWriter writer = new QRCodeWriter();
            final Map<EncodeHintType,Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.ISO_8859_1);
            final int size = Math.min(imageView.getWidth(), imageView.getHeight());
            result = writer.encode(new String(contents, StandardCharsets.ISO_8859_1), BarcodeFormat.QR_CODE,
                    size, size, hints);
        } catch (WriterException e) {
            throw new RuntimeException(e);
        }

        final int width = result.getWidth();
        final int height = result.getHeight();
        final int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            final int offset = y * width;
            for (int x = 0; x < width; x++) {
                pixels[offset + x] = result.get(x, y) ? BLACK : WHITE;
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565);
    }

    @SuppressLint("InlinedApi")
    private void startQrScanner() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA},
                    PERMISSIONS_REQUEST_CAMERA);
        } else {
            if (stage == Stage.EnableRemoteVerify &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSIONS_REQUEST_POST_NOTIFICATIONS_REMOTE_VERIFY);
            } else {
                QRScannerActivityLauncher.launch(new Intent(this, QRScannerActivity.class));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startQrScanner();
            } else {
                snackbar.setText(R.string.camera_permission_denied).show();
            }
        } else if (requestCode == PERMISSIONS_REQUEST_POST_NOTIFICATIONS_REMOTE_VERIFY) {
            QRScannerActivityLauncher.launch(new Intent(this, QRScannerActivity.class));
        } else if (requestCode == PERMISSIONS_REQUEST_POST_NOTIFICATIONS_SUBMIT_SAMPLE) {
            SubmitSampleJob.schedule(this);
            snackbar.setText(R.string.schedule_submit_sample_success).show();
        }
    }

    private void setBackgroundResource(final int resid) {
        final View content = findViewById(R.id.content_attestation);
        backgroundResource = resid;
        content.setBackgroundResource(resid);
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);

        Log.d(TAG, "onActivityResult " + requestCode + " " + resultCode);

    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.menu_attestation, menu);
        menu.findItem(R.id.action_clear_auditee).setEnabled(isSupportedAuditee);
        canSubmitSample = potentialSupportedAuditee() && !BuildConfig.DEBUG;
        menu.findItem(R.id.action_submit_sample).setEnabled(canSubmitSample);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        final boolean isRemoteVerifyEnabled = RemoteVerifyJob.isEnabled(this);
        menu.findItem(R.id.action_enable_remote_verify)
                .setEnabled(isSupportedAuditee && !isRemoteVerifyEnabled);
        menu.findItem(R.id.action_disable_remote_verify).setEnabled(isRemoteVerifyEnabled);
        menu.findItem(R.id.action_submit_sample).setEnabled(canSubmitSample &&
                !SubmitSampleJob.isScheduled(this));
        return true;
    }

    @Override
    @SuppressLint("InlinedApi")
    public boolean onOptionsItemSelected(final MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == R.id.action_clear_auditee) {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.action_clear_auditee) + "?")
                    .setPositiveButton(R.string.clear, (dialogInterface, i) -> {
                        executor.submit(() -> {
                            try {
                                AttestationProtocol.clearAuditee();
                                runOnUiThread(() -> snackbar.setText(R.string.clear_auditee_pairings_success).show());
                            } catch (final GeneralSecurityException | IOException e) {
                                Log.e(TAG, "clearAuditee", e);
                                runOnUiThread(() -> snackbar.setText(R.string.clear_auditee_pairings_failure).show());
                            }
                        });
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        } else if (itemId == R.id.action_clear_auditor) {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.action_clear_auditor) + "?")
                    .setPositiveButton(R.string.clear, (dialogInterface, i) -> {
                        executor.submit(() -> {
                            AttestationProtocol.clearAuditor(this);
                            runOnUiThread(() -> snackbar.setText(R.string.clear_auditor_pairings_success).show());
                        });
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        } else if (itemId == R.id.action_enable_remote_verify) {
            stage = Stage.EnableRemoteVerify;
            startQrScanner();
            return true;
        } else if (itemId == R.id.action_disable_remote_verify) {
            new AlertDialog.Builder(this)
                    .setMessage(getString(R.string.action_disable_remote_verify) + "?")
                    .setPositiveButton(R.string.disable, (dialogInterface, i) -> {
                        RemoteVerifyJob.executor.submit(() -> {
                            final SharedPreferences preferences =
                                    PreferenceManager.getDefaultSharedPreferences(this);
                            RemoteVerifyJob.cancel(this);

                            final long userId = preferences.getLong(RemoteVerifyJob.KEY_USER_ID, -1);

                            if (userId != -1) {
                                try {
                                    AttestationProtocol.clearAuditee(RemoteVerifyJob.STATE_PREFIX, Long.toString(userId));
                                } catch (final GeneralSecurityException | IOException e) {
                                    Log.e(TAG, "clearAuditee", e);
                                }
                            }

                            preferences.edit()
                                    .remove(RemoteVerifyJob.KEY_USER_ID)
                                    .remove(RemoteVerifyJob.KEY_SUBSCRIBE_KEY)
                                    .apply();

                            snackbar.setText(R.string.disable_remote_verify_success).show();
                        });
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return true;
        } else if (itemId == R.id.action_submit_sample) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSIONS_REQUEST_POST_NOTIFICATIONS_SUBMIT_SAMPLE);
            } else {
                SubmitSampleJob.schedule(this);
                snackbar.setText(R.string.schedule_submit_sample_success).show();
            }
            return true;
        } else if (itemId == R.id.action_help) {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TUTORIAL_URL)));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (stage == Stage.AuditeeResults || stage == Stage.Auditor ||
                stage == Stage.Result) {
            auditeeSerializedAttestation = null;
            auditorChallenge = null;
            stage = Stage.None;
            textView.setText("");
            backgroundResource = 0;
            recreate();
            return;
        }
        super.onBackPressed();
    }
}
