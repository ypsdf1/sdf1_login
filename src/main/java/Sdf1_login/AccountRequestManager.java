package Sdf1_login;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AccountRequestManager {

    public enum ApprovalMode { AUTO, MANUAL, HYBRID }

    public static class Request {
        public final int id;
        public final String requester;
        public final String target;
        public final String ip;
        public final long time;
        public String status;
        public String denyReason;

        public Request(int id, String requester, String target, String ip) {
            this.id = id;
            this.requester = requester;
            this.target = target;
            this.ip = ip;
            this.time = System.currentTimeMillis();
            this.status = "PENDING";
            this.denyReason = "";
        }
    }

    private final Main plugin;
    private final Map<Integer, Request> requests = new ConcurrentHashMap<>();
    private final Map<String, Integer> pendingByPlayer = new ConcurrentHashMap<>();
    private int nextId = 1;
    private ApprovalMode mode = ApprovalMode.MANUAL;
    private int autoDelayMinutes = 60;

    public AccountRequestManager(Main plugin) {
        this.plugin = plugin;
    }

    public ApprovalMode getMode() { return mode; }

    public void setMode(String m) {
        try { this.mode = ApprovalMode.valueOf(m.toUpperCase()); }
        catch (Exception e) { this.mode = ApprovalMode.MANUAL; }
    }

    public void setAutoDelayMinutes(int min) {
        this.autoDelayMinutes = min;
    }

    public Request createRequest(String requester, String target, String ip) {
        int id = nextId++;
        Request req = new Request(id, requester, target, ip);
        requests.put(id, req);
        pendingByPlayer.put(requester, id);

        if (mode == ApprovalMode.AUTO) {
            scheduleAutoApprove(id);
        } else if (mode == ApprovalMode.HYBRID) {
            String targetIp = (String) plugin.getDb().getField(target, "ip_address");
            if (ip != null && ip.equals(targetIp)) {
                scheduleAutoApprove(id);
            }
        }
        return req;
    }

    private void scheduleAutoApprove(int id) {
        long delayTicks = (long) autoDelayMinutes * 60L * 20L;
        Request req = requests.get(id);
        if (req == null) return;
        String requesterName = req.requester;
        String targetName = req.target;
        plugin.getLogger().info("[Sdf1_login] OA#" + id + " 将在 " + autoDelayMinutes + " 分钟后自动通过");

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Request r = requests.get(id);
            if (r != null && "PENDING".equals(r.status)) {
                approveRequest(id);
                plugin.getLogger().info("[Sdf1_login] OA#" + id + " 自动通过");
                Player requester = Bukkit.getPlayer(requesterName);
                if (requester != null && requester.isOnline()) {
                    requester.sendMessage("§a[Sdf1_login] §f您的删除请求 #" + id + "（§e" + targetName + "§a）已自动通过！");
                }
            }
        }, delayTicks);
    }

    public boolean approveRequest(int id) {
        Request req = requests.get(id);
        if (req == null || !"PENDING".equals(req.status)) return false;
        req.status = "APPROVED";
        plugin.getDb().deleteUser(req.target);
        pendingByPlayer.remove(req.requester);
        // 清背包+传送出生点+踢出
        Player target = Bukkit.getPlayer(req.target);
        if (target != null && target.isOnline()) {
            target.getInventory().clear();
            target.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
            target.getInventory().setExtraContents(new org.bukkit.inventory.ItemStack[1]);
            target.teleport(target.getWorld().getSpawnLocation());
            target.kickPlayer("§c§l你的账号已被管理员删除，请重新注册");
        }
        return true;
    }


    public boolean denyRequest(int id, String reason) {
        Request req = requests.get(id);
        if (req == null || !"PENDING".equals(req.status)) return false;
        req.status = "DENIED";
        req.denyReason = reason;
        pendingByPlayer.remove(req.requester);
        return true;
    }

    public List<Request> getPendingRequests() {
        List<Request> list = new ArrayList<>();
        for (Request r : requests.values()) {
            if ("PENDING".equals(r.status)) list.add(r);
        }
        return list;
    }

    public Request getRequest(int id) {
        return requests.get(id);
    }

    public boolean hasPending(String player) {
        return pendingByPlayer.containsKey(player);
    }

    public void cancelPending(String player) {
        Integer id = pendingByPlayer.remove(player);
        if (id != null) {
            Request r = requests.get(id);
            if (r != null) r.status = "CANCELLED";
        }
    }
}
