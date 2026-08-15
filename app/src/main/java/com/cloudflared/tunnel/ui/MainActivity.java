package com.cloudflared.tunnel.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.cloudflared.tunnel.App;
import com.cloudflared.tunnel.R;
import com.cloudflared.tunnel.model.Config;
import com.cloudflared.tunnel.service.MonitorService;
import com.cloudflared.tunnel.utils.CloudflaredRunner;
import com.cloudflared.tunnel.utils.ConfigManager;
import com.cloudflared.tunnel.utils.RootUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String TAG = "MainActivity";

    private TextView tvStatus;
    private TextView tvStatusDetail;
    private TextView tvToken;
    private TextView tvBinaryPath;
    private TextView tvConfigCount;
    private Spinner spinnerConfigs;
    private MaterialButton btnToggle;
    private RecyclerView recyclerView;
    private ConfigAdapter configAdapter;
    private FloatingActionButton fabAdd;
    private ImageButton btnRefresh;
    private View statusIndicator;
    private ImageView ivStatusIcon;
    private LinearLayout emptyState;
    private MaterialSwitch switchRootMode;
    private TextView tvModeLabel;

    private ConfigManager configManager;
    private Handler handler;
    private ExecutorService executorService;
    private boolean spinnerInitialized = false;
    private BroadcastReceiver statusReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        configManager = new ConfigManager(this);
        handler = new Handler(Looper.getMainLooper());
        executorService = Executors.newSingleThreadExecutor();

        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                checkStatus();
            }
        };

        initViews();
        checkPermissions();
        startMonitorService();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
    }

    private void initViews() {
        tvStatus = findViewById(R.id.tv_status);
        tvStatusDetail = findViewById(R.id.tv_status_detail);
        tvToken = findViewById(R.id.tv_token);
        tvBinaryPath = findViewById(R.id.tv_binary_path);
        tvConfigCount = findViewById(R.id.tv_config_count);
        spinnerConfigs = findViewById(R.id.spinner_configs);
        btnToggle = findViewById(R.id.btn_toggle);
        recyclerView = findViewById(R.id.recycler_configs);
        fabAdd = findViewById(R.id.fab_add);
        btnRefresh = findViewById(R.id.btn_refresh);
        statusIndicator = findViewById(R.id.status_indicator);
        ivStatusIcon = findViewById(R.id.iv_status_icon);
        emptyState = findViewById(R.id.empty_state);
        switchRootMode = findViewById(R.id.switch_root_mode);
        tvModeLabel = findViewById(R.id.tv_mode_label);

        tvStatus.setText("检查中...");
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_unknown));
        setStatusIndicatorColor(R.color.status_unknown);
        btnToggle.setEnabled(false);

        boolean isRootMode = CloudflaredRunner.MODE_ROOT.equals(CloudflaredRunner.getRunMode(this));
        switchRootMode.setChecked(isRootMode);
        updateModeLabel(isRootMode);

        switchRootMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            String mode = isChecked ? CloudflaredRunner.MODE_ROOT : CloudflaredRunner.MODE_NON_ROOT;
            CloudflaredRunner.setRunMode(this, mode);
            updateModeLabel(isChecked);
            checkStatus();
        });

        btnToggle.setOnClickListener(v -> toggleService());
        fabAdd.setOnClickListener(v -> addNewConfig());
        btnRefresh.setOnClickListener(v -> checkStatus());

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        configAdapter = new ConfigAdapter();
        recyclerView.setAdapter(configAdapter);

        spinnerConfigs.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Config config = (Config) parent.getItemAtPosition(position);
                if (config != null) {
                    displayConfig(config);
                    if (spinnerInitialized) {
                        Config currentDefault = configManager.getDefaultConfig();
                        if (currentDefault == null || !currentDefault.getId().equals(config.getId())) {
                            setDefaultConfig(config);
                        }
                    }
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void updateModeLabel(boolean isRoot) {
        if (isRoot) {
            tvModeLabel.setText("ROOT 模式");
            tvModeLabel.setTextColor(ContextCompat.getColor(this, R.color.color_primary));
        } else {
            tvModeLabel.setText("免 ROOT 模式");
            tvModeLabel.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        }
    }

    private void setStatusIndicatorColor(int colorRes) {
        if (statusIndicator != null && statusIndicator.getBackground() instanceof GradientDrawable) {
            GradientDrawable drawable = (GradientDrawable) statusIndicator.getBackground();
            drawable.setColor(ContextCompat.getColor(this, colorRes));
        }
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void startMonitorService() {
        Intent serviceIntent = new Intent(this, MonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadConfigs();
        registerReceiver(statusReceiver, new IntentFilter(App.ACTION_STATUS_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        handler.postDelayed(this::checkStatus, 300);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
    }

    private void checkStatus() {
        if (executorService == null || executorService.isShutdown()) return;
        if (btnRefresh == null) return;

        btnRefresh.setEnabled(false);
        executorService.execute(() -> {
            boolean isRunning = CloudflaredRunner.isRunning(this);
            String state = CloudflaredRunner.getState(this);
            String error = CloudflaredRunner.getError(this);
            handler.post(() -> {
                btnRefresh.setEnabled(true);
                updateUIStatus(isRunning, state, error);
            });
        });
    }

    private void updateUIStatus(boolean isRunning, String state, String error) {
        if (isRunning) {
            tvStatus.setText("运行中");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_running));
            setStatusIndicatorColor(R.color.status_running);
            btnToggle.setText("停止服务");
            btnToggle.setIconResource(R.drawable.ic_stop);
            btnToggle.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.color_stop));
            ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_running));

            if ("connected".equals(state)) {
                tvStatusDetail.setText("已连接 Cloudflare 边缘");
                tvStatusDetail.setTextColor(ContextCompat.getColor(this, R.color.status_running));
            } else {
                tvStatusDetail.setText("正在连接...");
                tvStatusDetail.setTextColor(ContextCompat.getColor(this, R.color.status_unknown));
            }
        } else {
            tvStatus.setText("已停止");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_stopped));
            setStatusIndicatorColor(R.color.status_stopped);
            btnToggle.setText("启动服务");
            btnToggle.setIconResource(R.drawable.ic_play);
            btnToggle.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.color_start));
            ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.status_stopped));
            tvStatusDetail.setText(error != null && !error.isEmpty() ? error : "隧道未运行");
            tvStatusDetail.setTextColor(ContextCompat.getColor(this, R.color.status_stopped));
        }
        btnToggle.setEnabled(true);
    }

    private void loadConfigs() {
        List<Config> configs = configManager.getAllConfigs();
        configAdapter.setConfigs(configs);
        tvConfigCount.setText(configs.size() + " 个配置");

        if (configs.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyState.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
        }

        ArrayAdapter<Config> spinnerAdapter = new ArrayAdapter<>(this,
                R.layout.item_spinner, configs);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerConfigs.setAdapter(spinnerAdapter);

        spinnerInitialized = false;
        Config defaultConfig = configManager.getDefaultConfig();
        if (defaultConfig != null && !configs.isEmpty()) {
            for (int i = 0; i < configs.size(); i++) {
                if (configs.get(i).getId().equals(defaultConfig.getId())) {
                    spinnerConfigs.setSelection(i);
                    displayConfig(defaultConfig);
                    break;
                }
            }
        } else if (configs.isEmpty()) {
            tvToken.setText("Token: -");
            tvBinaryPath.setText("路径: -");
        }
        spinnerInitialized = true;
    }

    private void displayConfig(Config config) {
        if (config.isLocal()) {
            tvToken.setText("模式: 本地管理 (配置文件)");
            String yaml = config.getConfigYaml();
            if (yaml != null && !yaml.trim().isEmpty()) {
                String firstLine = yaml.trim().split("\n")[0];
                tvBinaryPath.setText("配置: " + firstLine);
            } else {
                tvBinaryPath.setText("配置: -");
            }
        } else {
            tvToken.setText("模式: 远程管理 (Token)");
            String token = config.getToken();
            if (token != null && !token.isEmpty()) {
                tvBinaryPath.setText("Token: " + maskToken(token));
            } else {
                tvBinaryPath.setText("Token: -");
            }
        }
    }

    private String maskToken(String token) {
        if (token == null || token.length() <= 8) return "****";
        return token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }

    private void toggleService() {
        Config config = (Config) spinnerConfigs.getSelectedItem();
        if (config == null) {
            Toast.makeText(this, "请先创建配置", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean isRootMode = switchRootMode.isChecked();
        if (isRootMode) {
            if (!RootUtils.isRootAvailable()) {
                Toast.makeText(this, "设备未Root，请切换到免ROOT模式", Toast.LENGTH_LONG).show();
                return;
            }
        }

        btnToggle.setEnabled(false);
        tvStatus.setText("处理中...");
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_unknown));
        setStatusIndicatorColor(R.color.status_unknown);

        executorService.execute(() -> {
            boolean isRunning = CloudflaredRunner.isRunning(this);
            if (isRunning) {
                boolean ok = CloudflaredRunner.stop(this);
                handler.post(() -> {
                    Toast.makeText(this, ok ? "隧道已停止" : "停止失败", Toast.LENGTH_SHORT).show();
                    handler.postDelayed(this::checkStatus, 1000);
                });
            } else {
                boolean ok = CloudflaredRunner.start(this, config);
                handler.post(() -> {
                    if (ok) {
                        Toast.makeText(this, "隧道启动中...", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "启动失败", Toast.LENGTH_LONG).show();
                    }
                    handler.postDelayed(this::checkStatus, 1500);
                });
            }
        });
    }

    private void addNewConfig() {
        startActivity(new Intent(this, ConfigEditActivity.class));
    }

    private void editConfig(Config config) {
        Intent intent = new Intent(this, ConfigEditActivity.class);
        intent.putExtra("config_id", config.getId());
        startActivity(intent);
    }

    private void deleteConfig(Config config) {
        new AlertDialog.Builder(this)
                .setTitle("删除配置")
                .setMessage("确定删除 \"" + config.getName() + "\" ？")
                .setPositiveButton("删除", (d, w) -> {
                    configManager.deleteConfig(config.getId());
                    loadConfigs();
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setDefaultConfig(Config config) {
        configManager.setDefaultConfig(config.getId());
        loadConfigs();
    }

    private class ConfigAdapter extends RecyclerView.Adapter<ConfigAdapter.ViewHolder> {

        private List<Config> configs = new ArrayList<>();

        void setConfigs(List<Config> configs) {
            this.configs = configs;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_config, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Config config = configs.get(position);
            holder.tvName.setText(config.getName());
            if (config.isLocal()) {
                holder.tvServer.setText("本地管理 (配置文件)");
            } else if (config.hasToken()) {
                holder.tvServer.setText("远程管理 (Token)");
            } else {
                holder.tvServer.setText("快速隧道");
            }
            holder.chipDefault.setVisibility(config.isDefault() ? View.VISIBLE : View.GONE);
            holder.itemView.setOnClickListener(v -> editConfig(config));
            holder.itemView.setOnLongClickListener(v -> {
                showConfigOptions(config);
                return true;
            });
        }

        @Override
        public int getItemCount() { return configs.size(); }

        private void showConfigOptions(Config config) {
            String[] options = {"编辑", "设为默认", "删除"};
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(config.getName())
                    .setItems(options, (d, which) -> {
                        switch (which) {
                            case 0: editConfig(config); break;
                            case 1: setDefaultConfig(config); break;
                            case 2: deleteConfig(config); break;
                        }
                    })
                    .show();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName;
            TextView tvServer;
            Chip chipDefault;

            ViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(R.id.tv_config_name);
                tvServer = itemView.findViewById(R.id.tv_config_server);
                chipDefault = itemView.findViewById(R.id.chip_default);
            }
        }
    }
}
