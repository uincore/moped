package dao;


import java.util.List;
import model.AppConfig;
import model.PluginConfig;
import model.PluginLinkConfig;
import model.PluginPortConfig;

public interface AppConfigDao {
	public void saveAppConfig(AppConfig appConfig);
	public void savePluginConfig(PluginConfig pluginConfig);
	public void savePluginPortConfig(PluginPortConfig pluginPortConfig);
	public void savePluginLinkConfig(PluginLinkConfig pluginLinkConfig);
	public AppConfig getAppConfig(int appId, String vehicleName, String vehicleBrand);
	public List<AppConfig> getAppConfigs(int appId);
}
