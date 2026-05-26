package Sdf1_login;

import org.bukkit.Material;

public class ShopItem {
    private final String id;
    private final String displayName;
    private final Material material;
    private final int buyPrice;
    private final int sellPrice;
    private int stock;
    private int hourlySales;
    private int totalSales;
//打包了 直接测试替换就好了。
    public ShopItem(String id, String displayName,
                    Material material, int buyPrice,
                    int sellPrice, int stock,
                    int hourlySales, int totalSales) {
        this.id = id;
        this.displayName = displayName;
        this.material = material;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.stock = stock;
        this.hourlySales = hourlySales;
        this.totalSales = totalSales;
    }

    public int getEffectiveSellPrice() {
        if (sellPrice <= 0) return 0;
        return sellPrice;
    }

    public String getId()          { return id; }
    public String getDisplayName() { return displayName; }
    public Material getMaterial()  { return material; }
    public int getBuyPrice()       { return buyPrice; }
    public int getSellPrice()      { return sellPrice; }
    public int getStock()          { return stock; }
    public int getHourlySales()    { return hourlySales; }
    public int getTotalSales()     { return totalSales; }

    public void setStock(int s)       { this.stock = s; }
    public void setHourlySales(int s) { this.hourlySales = s; }
    public void setTotalSales(int s)  { this.totalSales = s; }
    public void addSales(int n) {
        this.hourlySales += n;
        this.totalSales += n;
    }
}
