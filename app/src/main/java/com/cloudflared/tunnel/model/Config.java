package com.cloudflared.tunnel.model;

public class Config {

    public static final String TYPE_REMOTE = "remote";
    public static final String TYPE_LOCAL = "local";

    private String id;
    private String name;
    private String token;
    private String protocol;
    private boolean isDefault;
    private long createdAt;
    private String type = TYPE_REMOTE;
    private String configYaml;
    private String credentialsJson;

    public Config() {
        this.id = String.valueOf(System.currentTimeMillis());
        this.protocol = "auto";
        this.isDefault = false;
        this.createdAt = System.currentTimeMillis();
        this.type = TYPE_REMOTE;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean aDefault) { isDefault = aDefault; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public boolean hasToken() {
        return token != null && !token.trim().isEmpty();
    }

    public String getType() { return type != null ? type : TYPE_REMOTE; }
    public void setType(String type) { this.type = type; }

    public String getConfigYaml() { return configYaml; }
    public void setConfigYaml(String configYaml) { this.configYaml = configYaml; }

    public String getCredentialsJson() { return credentialsJson; }
    public void setCredentialsJson(String credentialsJson) { this.credentialsJson = credentialsJson; }

    public boolean isLocal() { return TYPE_LOCAL.equals(getType()); }

    @Override
    public String toString() {
        return name != null ? name : "未命名配置";
    }
}
