package com.cloudflared.tunnel.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.cloudflared.tunnel.R;
import com.cloudflared.tunnel.model.Config;
import com.cloudflared.tunnel.utils.CloudflaredRunner;
import com.cloudflared.tunnel.utils.ConfigManager;

public class ConfigEditActivity extends AppCompatActivity {

    private EditText etName;
    private EditText etToken;
    private EditText etConfigYaml;
    private EditText etCredentialsJson;
    private EditText etTunnelName;
    private Spinner spinnerConfigType;
    private Spinner spinnerProtocol;
    private CheckBox cbDefault;
    private Button btnSave;
    private Button btnDelete;
    private Button btnLogin;
    private Button btnCreateTunnel;
    private TextView tvLoginStatus;
    private LinearLayout layoutRemote;
    private LinearLayout layoutLocal;

    private ConfigManager configManager;
    private Config config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_config_edit);

        configManager = new ConfigManager(this);

        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        etName = findViewById(R.id.et_name);
        etToken = findViewById(R.id.et_token);
        etConfigYaml = findViewById(R.id.et_config_yaml);
        etCredentialsJson = findViewById(R.id.et_credentials_json);
        etTunnelName = findViewById(R.id.et_tunnel_name);
        spinnerConfigType = findViewById(R.id.spinner_config_type);
        spinnerProtocol = findViewById(R.id.spinner_protocol);
        cbDefault = findViewById(R.id.cb_default);
        btnSave = findViewById(R.id.btn_save);
        btnDelete = findViewById(R.id.btn_delete);
        btnLogin = findViewById(R.id.btn_login);
        btnCreateTunnel = findViewById(R.id.btn_create_tunnel);
        tvLoginStatus = findViewById(R.id.tv_login_status);
        layoutRemote = findViewById(R.id.layout_remote);
        layoutLocal = findViewById(R.id.layout_local);

        boolean isRoot = CloudflaredRunner.MODE_ROOT.equals(CloudflaredRunner.getRunMode(this));
        String[] typeOptions = isRoot
                ? new String[]{"远程管理 (Token)", "本地管理 (配置文件)"}
                : new String[]{"远程管理 (Token)"};
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, typeOptions);
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerConfigType.setAdapter(typeAdapter);

        spinnerConfigType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == 1) {
                    layoutRemote.setVisibility(View.GONE);
                    layoutLocal.setVisibility(View.VISIBLE);
                    if (etConfigYaml.getText().toString().trim().isEmpty()) {
                        etConfigYaml.setText(getDefaultYamlTemplate());
                    }
                    checkLoginStatus();
                } else {
                    layoutRemote.setVisibility(View.VISIBLE);
                    layoutLocal.setVisibility(View.GONE);
                }
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        ArrayAdapter<String> protocolAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"auto", "http2", "quic"});
        protocolAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProtocol.setAdapter(protocolAdapter);

        String configId = getIntent().getStringExtra("config_id");
        if (configId != null) {
            config = configManager.getConfig(configId);
            if (config != null) {
                etName.setText(config.getName());
                etToken.setText(config.getToken());
                etConfigYaml.setText(config.getConfigYaml());
                etCredentialsJson.setText(config.getCredentialsJson());

                if (Config.TYPE_LOCAL.equals(config.getType()) && isRoot) {
                    spinnerConfigType.setSelection(1);
                } else {
                    spinnerConfigType.setSelection(0);
                }

                String protocol = config.getProtocol();
                if (protocol != null) {
                    String[] protocols = {"auto", "http2", "quic"};
                    for (int i = 0; i < protocols.length; i++) {
                        if (protocols[i].equals(protocol)) {
                            spinnerProtocol.setSelection(i);
                            break;
                        }
                    }
                }
                cbDefault.setChecked(config.isDefault());
                btnDelete.setVisibility(View.VISIBLE);
            }
        } else {
            config = new Config();
            btnDelete.setVisibility(View.GONE);
        }

        btnSave.setOnClickListener(v -> saveConfig());
        btnDelete.setOnClickListener(v -> confirmDelete());
        btnLogin.setOnClickListener(v -> startLogin());
        btnCreateTunnel.setOnClickListener(v -> createTunnel());
    }

    private void checkLoginStatus() {
        boolean loggedIn = CloudflaredRunner.isLoggedIn(this);
        if (loggedIn) {
            tvLoginStatus.setText("已登录 Cloudflare");
            tvLoginStatus.setTextColor(ContextCompat.getColor(this, R.color.status_running));
            btnLogin.setVisibility(View.GONE);
            btnCreateTunnel.setEnabled(true);
        } else {
            tvLoginStatus.setText("未登录");
            tvLoginStatus.setTextColor(ContextCompat.getColor(this, R.color.status_stopped));
            btnLogin.setVisibility(View.VISIBLE);
            btnCreateTunnel.setEnabled(false);
        }
    }

    private void startLogin() {
        btnLogin.setEnabled(false);
        btnLogin.setText("登录中...");
        tvLoginStatus.setText("正在启动登录...");
        tvLoginStatus.setTextColor(ContextCompat.getColor(this, R.color.status_unknown));

        CloudflaredRunner.login(this, new CloudflaredRunner.LoginCallback() {
            @Override
            public void onUrlReady(String url) {
                tvLoginStatus.setText("请在浏览器中完成登录");
                tvLoginStatus.setTextColor(ContextCompat.getColor(ConfigEditActivity.this, R.color.status_unknown));
                try {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(browserIntent);
                } catch (Exception e) {
                    new AlertDialog.Builder(ConfigEditActivity.this)
                            .setTitle("登录 URL")
                            .setMessage(url)
                            .setPositiveButton("复制并打开", (d, w) -> {
                                android.content.ClipboardManager clipboard =
                                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                android.content.ClipData clip = android.content.ClipData.newPlainText("url", url);
                                clipboard.setPrimaryClip(clip);
                                Toast.makeText(ConfigEditActivity.this, "URL已复制", Toast.LENGTH_SHORT).show();
                            })
                            .show();
                }
            }

            @Override
            public void onSuccess() {
                btnLogin.setEnabled(true);
                btnLogin.setText("登录 Cloudflare");
                checkLoginStatus();
                Toast.makeText(ConfigEditActivity.this, "登录成功", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                btnLogin.setEnabled(true);
                btnLogin.setText("登录 Cloudflare");
                tvLoginStatus.setText("登录失败: " + error);
                tvLoginStatus.setTextColor(ContextCompat.getColor(ConfigEditActivity.this, R.color.status_stopped));
                Toast.makeText(ConfigEditActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void createTunnel() {
        String name = etTunnelName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "请输入隧道名称", Toast.LENGTH_SHORT).show();
            return;
        }

        btnCreateTunnel.setEnabled(false);
        btnCreateTunnel.setText("创建中...");
        tvLoginStatus.setText("正在创建隧道...");
        tvLoginStatus.setTextColor(ContextCompat.getColor(this, R.color.status_unknown));

        CloudflaredRunner.createTunnel(this, name, new CloudflaredRunner.CreateTunnelCallback() {
            @Override
            public void onSuccess(String tunnelId, String credentialsJson) {
                btnCreateTunnel.setEnabled(true);
                btnCreateTunnel.setText("创建隧道并自动生成配置");

                etCredentialsJson.setText(credentialsJson);
                etConfigYaml.setText(generateYaml(tunnelId));
                tvLoginStatus.setText("隧道创建成功: " + tunnelId);
                tvLoginStatus.setTextColor(ContextCompat.getColor(ConfigEditActivity.this, R.color.status_running));

                if (etName.getText().toString().trim().isEmpty()) {
                    etName.setText(name);
                }
                Toast.makeText(ConfigEditActivity.this, "隧道 " + tunnelId + " 创建成功", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String error) {
                btnCreateTunnel.setEnabled(true);
                btnCreateTunnel.setText("创建隧道并自动生成配置");
                tvLoginStatus.setText("创建失败: " + error);
                tvLoginStatus.setTextColor(ContextCompat.getColor(ConfigEditActivity.this, R.color.status_stopped));
                Toast.makeText(ConfigEditActivity.this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private String generateYaml(String tunnelId) {
        return "tunnel: " + tunnelId + "\n"
                + "credentials-file: credentials.json\n"
                + "ingress:\n"
                + "  - hostname: " + tunnelId + ".example.com\n"
                + "    service: http://localhost:80\n"
                + "  - service: http_status:404\n";
    }

    private String getDefaultYamlTemplate() {
        return "tunnel: <Tunnel-UUID>\n"
                + "credentials-file: credentials.json\n"
                + "ingress:\n"
                + "  - hostname: example.com\n"
                + "    service: http://localhost:80\n"
                + "  - service: http_status:404\n";
    }

    private void saveConfig() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "请输入配置名称", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean isLocal = spinnerConfigType.getSelectedItemPosition() == 1;

        config.setName(name);
        config.setType(isLocal ? Config.TYPE_LOCAL : Config.TYPE_REMOTE);
        config.setProtocol((String) spinnerProtocol.getSelectedItem());
        config.setDefault(cbDefault.isChecked());

        if (isLocal) {
            String yaml = etConfigYaml.getText().toString().trim();
            if (yaml.isEmpty()) {
                Toast.makeText(this, "请输入配置文件内容", Toast.LENGTH_SHORT).show();
                return;
            }
            config.setConfigYaml(yaml);
            config.setCredentialsJson(etCredentialsJson.getText().toString().trim());
            config.setToken(null);
        } else {
            config.setToken(etToken.getText().toString().trim());
            config.setConfigYaml(null);
            config.setCredentialsJson(null);
        }

        configManager.saveConfig(config);
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除配置")
                .setMessage("确定删除 \"" + config.getName() + "\" ？")
                .setPositiveButton("删除", (d, w) -> {
                    configManager.deleteConfig(config.getId());
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
