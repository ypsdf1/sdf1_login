package Sdf1_login;

import java.util.ArrayList;
import java.util.List;

public class ShopCategory {
    private final String name;
    private final String fileName;
    private final List<ShopItem> items;

    public ShopCategory(String name, String fileName) {
        this.name = name;
        this.fileName = fileName;
        this.items = new ArrayList<>();
    }

    public String getName()          { return name; }
    public String getFileName()      { return fileName; }
    public List<ShopItem> getItems() { return items; }

    public ShopItem getItem(String id) {
        for (ShopItem item : items) {
            if (item.getId().equalsIgnoreCase(id))
                return item;
        }
        return null;
    }

    public void addItem(ShopItem item) {
        items.add(item);
    }
}
