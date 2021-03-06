package sh.okx.rankup;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import sh.okx.rankup.commands.*;
import sh.okx.rankup.economy.Economy;
import sh.okx.rankup.economy.EconomyProvider;
import sh.okx.rankup.economy.VaultEconomyProvider;
import sh.okx.rankup.gui.Gui;
import sh.okx.rankup.gui.GuiListener;
import sh.okx.rankup.hook.GroupProvider;
import sh.okx.rankup.hook.PermissionManager;
import sh.okx.rankup.hook.VaultPermissionManager;
import sh.okx.rankup.messages.Message;
import sh.okx.rankup.messages.MessageBuilder;
import sh.okx.rankup.messages.NullMessageBuilder;
import sh.okx.rankup.messages.Variable;
import sh.okx.rankup.placeholders.Placeholders;
import sh.okx.rankup.prestige.Prestige;
import sh.okx.rankup.prestige.Prestiges;
import sh.okx.rankup.ranks.Rank;
import sh.okx.rankup.ranks.RankList;
import sh.okx.rankup.ranks.Rankups;
import sh.okx.rankup.requirements.Requirement;
import sh.okx.rankup.requirements.RequirementRegistry;
import sh.okx.rankup.requirements.requirement.XpLevelDeductibleRequirement;
import sh.okx.rankup.requirements.requirement.*;
import sh.okx.rankup.requirements.requirement.advancedachievements.AdvancedAchievementsAchievementRequirement;
import sh.okx.rankup.requirements.requirement.advancedachievements.AdvancedAchievementsTotalRequirement;
import sh.okx.rankup.requirements.requirement.mcmmo.McMMOPowerLevelRequirement;
import sh.okx.rankup.requirements.requirement.mcmmo.McMMOSkillRequirement;
import sh.okx.rankup.requirements.requirement.tokenmanager.TokensDeductibleRequirement;
import sh.okx.rankup.requirements.requirement.tokenmanager.TokensRequirement;
import sh.okx.rankup.requirements.requirement.towny.*;
import sh.okx.rankup.requirements.requirement.votingplugin.VotingPluginPointsDeductibleRequirement;
import sh.okx.rankup.requirements.requirement.votingplugin.VotingPluginPointsRequirement;
import sh.okx.rankup.requirements.requirement.votingplugin.VotingPluginVotesRequirement;
import sh.okx.rankup.util.UpdateNotifier;
import sh.okx.rankup.util.VersionChecker;

import java.io.File;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class RankupPlugin extends JavaPlugin {

  public static final int CONFIG_VERSION = 8;

  @Getter
  private GroupProvider permissions;
  @Getter
  private Economy economy;
  /**
   * The registry for listing the requirements to /rankup.
   */
  @Getter
  private RequirementRegistry requirements;
  @Getter
  private FileConfiguration messages;
  @Getter
  private FileConfiguration config;
  @Getter
  private Rankups rankups;
  @Getter
  private Prestiges prestiges;
  @Getter
  private Placeholders placeholders;
  @Getter
  private RankupHelper helper;
  protected AutoRankup autoRankup = new AutoRankup(this);
  private String errorMessage;
  private PermissionManager permissionManager = new VaultPermissionManager(this);
  private EconomyProvider economyProvider = new VaultEconomyProvider();

  public RankupPlugin() {
    super();
  }

  protected RankupPlugin(JavaPluginLoader loader, PluginDescriptionFile description, File dataFolder, File file, PermissionManager permissionManager, EconomyProvider economyProvider) {
    super(loader, description, dataFolder, file);
    this.permissionManager = permissionManager;
    this.economyProvider = economyProvider;
  }

  @Override
  public void onEnable() {
    UpdateNotifier notifier = new UpdateNotifier(new VersionChecker(this));

    reload(true);

    if (System.getProperty("TEST") == null) {
      Metrics metrics = new Metrics(this);
      metrics.addCustomChart(new Metrics.SimplePie("confirmation",
              () -> config.getString("confirmation-type", "unknown")));
      metrics.addCustomChart(new Metrics.AdvancedPie("requirements", () -> {
        Map<String, Integer> map = new HashMap<>();
        addAll(map, rankups);
        if (prestiges != null) {
          addAll(map, prestiges);
        }
        return map;
      }));
      metrics.addCustomChart(new Metrics.SimplePie("prestige",
              () -> config.getBoolean("prestige") ? "enabled" : "disabled"));
      metrics.addCustomChart(new Metrics.SimplePie("permission-rankup",
              () -> config.getBoolean("permission-rankup") ? "enabled" : "disabled"));
      metrics.addCustomChart(new Metrics.SimplePie("notify-update",
              () -> config.getBoolean("notify-update") ? "enabled" : "disabled"));
    }

    if (config.getBoolean("ranks")) {
      getCommand("ranks").setExecutor(new RanksCommand(this));
    }
    if (config.getBoolean("prestige")) {
      getCommand("prestige").setExecutor(new PrestigeCommand(this));
      if (config.getBoolean("prestiges")) {
        getCommand("prestiges").setExecutor(new PrestigesCommand(this));
      }
    }
    if (config.getBoolean("max-rankup.enabled")) {
      getCommand("maxrankup").setExecutor(new MaxRankupCommand(this));
    }

    getCommand("rankup").setExecutor(new RankupCommand(this));
    getCommand("rankup3").setExecutor(new InfoCommand(this, notifier));
    getServer().getPluginManager().registerEvents(new GuiListener(this), this);
    getServer().getPluginManager().registerEvents(
        new JoinUpdateNotifier(notifier, () -> getConfig().getBoolean("notify-update"), "rankup.notify"), this);

    placeholders = new Placeholders(this);
    placeholders.register();
  }


  @Override
  public void onDisable() {
    closeInventories();
    if (placeholders != null) {
      placeholders.unregister();
    }
  }

  public void reload(boolean init) {
    errorMessage = null;

    config = loadConfig("config.yml");

    if (config.getBoolean("permission-rankup")) {
      permissions = permissionManager.permissionOnlyProvider();
    } else {
      permissions = permissionManager.findPermissionProvider();
      if (permissions == null) {
        errorMessage = "No permission plugin found";
      }
    }

    setupEconomy();

    closeInventories();
    loadConfigs(init);

    long time = (long) (config.getDouble("autorankup-interval") * 60 * 20);
    if (time > 0) {
      try {
        if (!autoRankup.isCancelled()) {
          autoRankup.cancel();
        }
      } catch (IllegalStateException ignored) {
      }
      autoRankup = new AutoRankup(this);
      autoRankup.runTaskTimer(this, time, time);
    }

    if (config.getInt("version") < CONFIG_VERSION) {
      getLogger().severe("You are using an outdated config!");
      getLogger().severe("This means that some things might not work!");
      getLogger().severe("To update, please rename ALL your config files (or the folder they are in),");
      getLogger().severe("and run /pru reload to generate a new config file.");
      getLogger().severe("If that does not work, restart your server.");
      getLogger().severe("You may then copy in your config values manually from the old config.");
      getLogger().severe("Check the changelog on the Rankup spigot page to see the changes.");
      getLogger().severe("https://www.spigotmc.org/resources/rankup.76964/updates");
    }

    helper = new RankupHelper(this);
  }

  public boolean error() {
    return error(null);
  }

  /**
   * Notify the player of an error if there is one
   *
   * @return true if there was an error and action was taken
   */
  public boolean error(CommandSender sender) {
    if (errorMessage == null) {
      return false;
    }

    if (sender instanceof Player) {
      sender.sendMessage(
          ChatColor.RED + "Could not load Rankup, check console for more information.");
    } else {
      getLogger().severe("Failed to load Rankup");
    }
    for (String line : errorMessage.split("\n")) {
      getLogger().severe(line);
    }
    getLogger().severe("More information can be found in the console log at startup");
    return true;
  }

  private void addAll(Map<String, Integer> map, RankList<? extends Rank> ranks) {
    for (Rank rank : ranks.getTree()) {
      for (Requirement requirement : rank.getRequirements().getRequirements(null)) {
        String name = requirement.getName();
        map.put(name, map.getOrDefault(name, 0) + 1);
      }
    }
  }

  /**
   * Closes all rankup inventories on disable so players cannot grab items from the inventory on a
   * plugin reload.
   */
  private void closeInventories() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      InventoryView view = player.getOpenInventory();
      if (view.getType() == InventoryType.CHEST
          && view.getTopInventory().getHolder() instanceof Gui) {
        player.closeInventory();
      }
    }
  }

  private void loadConfigs(boolean init) {
    saveLocales();

    String locale = config.getString("locale", "en");
    File localeFile = new File(new File(getDataFolder(), "locale"), locale + ".yml");
    messages = YamlConfiguration.loadConfiguration(localeFile);

    if (init) {
      Bukkit.getScheduler().runTask(this, () -> {
        refreshRanks();
        error();
      });
    } else {
      refreshRanks();
    }
  }

  public void refreshRanks() {
    try {
      registerRequirements();
      Bukkit.getPluginManager().callEvent(new RankupRegisterEvent(this));

      if (config.getBoolean("prestige")) {
        prestiges = new Prestiges(this, loadConfig("prestiges.yml"));
//        prestiges.getOrderedList();
      } else {
        prestiges = null;
      }

      rankups = new Rankups(this, loadConfig("rankups.yml"));
      // check rankups are not in an infinite loop
//      rankups.getOrderedList();



    } catch (Exception e) {
      this.errorMessage = e.getClass().getName() + ": " + e.getMessage();
      e.printStackTrace();
    }
  }

  private void saveLocales() {
    saveLocale("en");
    saveLocale("pt_br");
    saveLocale("ru");
    saveLocale("zh_cn");
    saveLocale("fr");
  }

  private void saveLocale(String locale) {
    String name = "locale/" + locale + ".yml";
    File file = new File(getDataFolder(), name);
    if (!file.exists()) {
      saveResource(name, false);
    }
  }

  private FileConfiguration loadConfig(String name) {
    File file = new File(getDataFolder(), name);
    if (!file.exists()) {
      saveResource(name, false);
    }
    return YamlConfiguration.loadConfiguration(file);
  }

  private void registerRequirements() {
    requirements = new RequirementRegistry();
    requirements.addRequirements(
        new XpLevelRequirement(this, "xp-levelh"),
        new XpLevelDeductibleRequirement(this, "xp-level"),
        new PlaytimeMinutesRequirement(this),
        new AdvancementRequirement(this),
        new GroupRequirement(this),
        new PermissionRequirement(this),
        new PlaceholderRequirement(this),
        new WorldRequirement(this),
        new BlockBreakRequirement(this),
        new PlayerKillsRequirement(this),
        new MobKillsRequirement(this),
        new ItemRequirement(this, "itemh"),
        new ItemDeductibleRequirement(this, "item"),
        new UseItemRequirement(this),
        new TotalMobKillsRequirement(this),
        new CraftItemRequirement(this));
    if (economy != null) {
      requirements.addRequirements(
          new MoneyRequirement(this, "moneyh"),
          new MoneyDeductibleRequirement(this, "money"));
    }

    PluginManager pluginManager = Bukkit.getPluginManager();
    if (pluginManager.isPluginEnabled("mcMMO")) {
      requirements.addRequirements(
          new McMMOSkillRequirement(this),
          new McMMOPowerLevelRequirement(this));
    }
    if (pluginManager.isPluginEnabled("AdvancedAchievements")) {
      requirements.addRequirements(
          new AdvancedAchievementsAchievementRequirement(this),
          new AdvancedAchievementsTotalRequirement(this));
    }
    if (pluginManager.isPluginEnabled("VotingPlugin")) {
      requirements.addRequirements(
          new VotingPluginVotesRequirement(this),
          new VotingPluginPointsRequirement(this, "votingplugin-pointsh"),
          new VotingPluginPointsDeductibleRequirement(this, "votingplugin-points"));
    }
    if (Bukkit.getPluginManager().isPluginEnabled("Towny")) {
      requirements.addRequirements(
          new TownyResidentRequirement(this),
          new TownyMayorRequirement(this),
          new TownyMayorNumberResidentsRequirement(this),
          new TownyKingRequirement(this),
          new TownyKingNumberResidentsRequirement(this),
          new TownyKingNumberTownsRequirement(this));
    }
    if (Bukkit.getPluginManager().isPluginEnabled("TokenManager")) {
      requirements.addRequirements(
          new TokensRequirement(this, "tokenmanager-tokensh"),
          new TokensDeductibleRequirement(this, "tokenmanager-tokens"));
    }
  }
  private void setupEconomy() {
    economy = economyProvider.getEconomy();
  }

  public String formatMoney(double money) {
    List<String> shortened = config.getStringList("shorten");
    String suffix = "";

    for (int i = shortened.size(); i > 0; i--) {
      double value = Math.pow(10, 3 * i);
      if (money >= value) {
        money /= value;
        suffix = shortened.get(i - 1);
        break;
      }
    }

    return placeholders.getMoneyFormat().format(money) + suffix;
  }

  public ConfigurationSection getSection(Rank rank, String path) {
    ConfigurationSection rankSection = rank.getSection();
    if (rankSection == null || !rankSection.isConfigurationSection(path)) {
      return this.messages.getConfigurationSection(path);
    }
    return rankSection.getConfigurationSection(path);
  }

  public MessageBuilder getMessage(Rank rank, Message message) {
    ConfigurationSection messages = rank.getSection();
    if (messages == null || !messages.isSet(message.getName())) {
      messages = this.messages;
    }
    return MessageBuilder.of(messages, message);
  }

  public MessageBuilder getMessage(Message message) {
    return MessageBuilder.of(messages, message);
  }

  public MessageBuilder replaceMoneyRequirements(MessageBuilder builder, CommandSender sender,
      Rank rank) {
    if (builder instanceof NullMessageBuilder) {
      return builder;
    }

    Requirement money = rank.getRequirement(sender instanceof Player ? (Player) sender : null, "money");
    if (money != null) {
      Double amount = null;
      if (sender instanceof Player && rank.isIn((Player) sender)) {
        if (economy != null) {
          amount = money.getRemaining((Player) sender);
        }
      } else {
        amount = money.getValueDouble();
      }
      if (amount != null && economy != null) {
        builder.replace(Variable.MONEY_NEEDED, formatMoney(amount));
        builder.replace(Variable.MONEY, formatMoney(money.getValueDouble()));
      }
    }
    if (sender instanceof Player) {
      replaceRequirements(builder, (Player) sender, rank);
    }
    return builder;
  }

  public MessageBuilder replaceRequirements(MessageBuilder builder, Player player, Rank rank) {
    DecimalFormat simpleFormat = placeholders.getSimpleFormat();
    DecimalFormat percentFormat = placeholders.getPercentFormat();
    for (Requirement requirement : rank.getRequirements().getRequirements(player)) {
      try {
        replaceRequirements(builder, Variable.AMOUNT, requirement,
            () -> simpleFormat.format(requirement.getTotal(player)));
        if (rank.isIn(player)) {
          replaceRequirements(builder, Variable.AMOUNT_NEEDED, requirement,
              () -> simpleFormat.format(requirement.getRemaining(player)));
          replaceRequirements(builder, Variable.PERCENT_LEFT, requirement,
              () -> percentFormat.format(Math.max(0,
                  (requirement.getRemaining(player) / requirement.getTotal(player)) * 100)));
          replaceRequirements(builder, Variable.PERCENT_DONE, requirement,
              () -> percentFormat.format(Math.min(100,
                  (1 - (requirement.getRemaining(player) / requirement.getTotal(player))) * 100)));
          replaceRequirements(builder, Variable.AMOUNT_DONE, requirement,
              () -> simpleFormat
                  .format(requirement.getTotal(player) - requirement.getRemaining(player)));
        }
      } catch (NumberFormatException ignored) {
      }
    }
    return builder;
  }

  private void replaceRequirements(MessageBuilder builder, Variable variable, Requirement requirement, Supplier<Object> value) {
    try {
      builder.replace(variable + " " + requirement.getFullName(), value.get());
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  public MessageBuilder getMessage(CommandSender player, Message message, Rank oldRank, Rank rankName) {
    String oldRankName;
    if (oldRank instanceof Prestige && oldRank.getRank() == null) {
      oldRankName = ((Prestige) oldRank).getFrom();
    } else {
      oldRankName = oldRank.getRank();
    }

    return replaceMoneyRequirements(getMessage(oldRank, message)
        .replaceRanks(player, rankName.getRank())
        .replace(Variable.OLD_RANK, oldRankName), player, oldRank)
        .replaceFromTo(oldRank);
  }

  public void sendHeaderFooter(CommandSender sender, Rank rank, Message type) {
    MessageBuilder builder;
    if (rank == null) {
      builder = getMessage(type)
          .failIfEmpty()
          .replace(Variable.PLAYER, sender.getName());
    } else {
      builder = getMessage(rank, type)
          .failIfEmpty()
          .replaceRanks(sender, rank.getRank())
          .replaceFromTo(rank);
    }
    builder.send(sender);
  }
}
