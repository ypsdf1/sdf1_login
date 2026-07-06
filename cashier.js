// ===== 收银台（管理员/收银员为玩家代购）=====
// 与 admin.php 共享全局作用域，依赖 jsonApi / postApi / toast / escAdmHtml 等全局函数。
let _cashierCart = [];
let _cashierCfg = {backpack_rate:0.98, shulker_rate:1.00};
let _cashierSettlement = 'backpack';
let _cashierColor = 'purple';
let _cashierItems = [];
let _cashierCategories = [];
let _cashierTarget = '';
let _cashierTargetBalance = null;

const CASHIER_COLORS = [
    {id:'purple', name:'紫色', price:0, css:'#AA00FF'},
    {id:'white',  name:'白色', price:2, css:'#E8E8E8'},
    {id:'black',  name:'黑色', price:2, css:'#2A2A2A'},
    {id:'red',    name:'红色', price:2, css:'#FF4444'},
    {id:'blue',   name:'蓝色', price:2, css:'#4488FF'},
    {id:'green',  name:'绿色', price:2, css:'#44FF77'},
    {id:'yellow', name:'黄色', price:2, css:'#FFEE44'},
    {id:'orange', name:'橙色', price:2, css:'#FF9944'}
];

function cashierMatIcon(mat) {
    const m = (mat || '').toUpperCase();
    if (m.indexOf('DIAMOND') >= 0) return '💎';
    if (m.indexOf('IRON') >= 0) return '⛏️';
    if (m.indexOf('GOLD') >= 0) return '🪙';
    if (m.indexOf('EMERALD') >= 0) return '🟢';
    if (m.indexOf('NETHERITE') >= 0) return '⬛';
    if (m.indexOf('WOOD') >= 0 || m.indexOf('PLANK') >= 0 || m.indexOf('LOG') >= 0) return '🪵';
    if (m.indexOf('STONE') >= 0 || m.indexOf('COBBLE') >= 0) return '🪨';
    if (m.indexOf('APPLE') >= 0 || m.indexOf('BREAD') >= 0 || m.indexOf('MEAT') >= 0 || m.indexOf('FISH') >= 0 || m.indexOf('PORK') >= 0 || m.indexOf('BEEF') >= 0 || m.indexOf('CHICKEN') >= 0) return '🍖';
    if (m.indexOf('POTION') >= 0 || m.indexOf('BOTTLE') >= 0) return '🧪';
    if (m.indexOf('BOOK') >= 0 || m.indexOf('ENCHANT') >= 0) return '📕';
    if (m.indexOf('SWORD') >= 0 || m.indexOf('AXE') >= 0 || m.indexOf('PICK') >= 0 || m.indexOf('SHOVEL') >= 0 || m.indexOf('HOE') >= 0) return '🗡️';
    if (m.indexOf('HELMET') >= 0 || m.indexOf('CHEST') >= 0 || m.indexOf('LEGGING') >= 0 || m.indexOf('BOOT') >= 0) return '🛡️';
    if (m.indexOf('BED') >= 0) return '🛏️';
    if (m.indexOf('TNT') >= 0) return '🧨';
    return '📦';
}

async function loadCashier(el) {
    el.innerHTML = '<div class="card" style="text-align:center;padding:40px">加载中...</div>';
    try { const r = await jsonApi('shop.php?action=cart_config'); if (r && r.success && r.data) _cashierCfg = r.data; } catch (e) {}
    try { const r = await jsonApi('shop.php?action=list'); _cashierItems = (r && r.data) ? r.data : []; } catch (e) { _cashierItems = []; }
    _cashierCategories = [...new Set(_cashierItems.map(i => i.category || '默认'))];
    _cashierTarget = '';
    _cashierTargetBalance = null;
    _cashierCart = [];
    _cashierSettlement = 'backpack';
    _cashierColor = 'purple';

    let catTabs = '';
    if (_cashierCategories.length > 0) {
        catTabs = '<div style="display:flex;gap:6px;flex-wrap:wrap;margin-bottom:12px">';
        _cashierCategories.forEach((cat, idx) => {
            const active = idx === 0 ? 'color:#fff;background:var(--accent);border-color:var(--accent)' : '';
            const cnt = _cashierItems.filter(i => (i.category || '默认') === cat).length;
            catTabs += '<div class="cashier-cat-tab" data-cat="' + escAdmHtml(cat) + '" style="padding:6px 14px;border:1px solid var(--border);border-radius:16px;cursor:pointer;font-size:13px;transition:all .2s;' + active + '">' + escAdmHtml(cat) + ' <span style="opacity:.7;font-size:11px">(' + cnt + ')</span></div>';
        });
        catTabs += '</div>';
    }

    el.innerHTML = `
    <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;flex-wrap:wrap;gap:8px">
        <h2 style="margin:0;color:var(--accent)">🧾 收银台 <span style="font-size:12px;color:var(--dim);font-weight:400">为玩家代购商品</span></h2>
    </div>
    <div style="display:grid;grid-template-columns:1fr 340px;gap:16px;align-items:start">
        <div>
            <div class="card">
                <h2 style="margin-bottom:10px">① 选择目标玩家</h2>
                <div class="form-row">
                    <input id="cashierPlayer" placeholder="输入玩家游戏名" onkeydown="if(event.key==='Enter')cashierCheckPlayer()" style="flex:1">
                    <button class="btn btn-blue" onclick="cashierCheckPlayer()">查询</button>
                </div>
                <div id="cashierTargetInfo" style="font-size:13px;color:var(--dim);margin-top:4px">输入玩家名后查询其债券余额</div>
            </div>
            <div class="card">
                <h2 style="margin-bottom:10px">② 选择商品</h2>
                ${catTabs}
                <div id="cashierItems" style="display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:10px"></div>
            </div>
        </div>
        <div class="card" style="position:sticky;top:12px">
            <h2 style="margin-bottom:10px">🛒 代购清单</h2>
            <div id="cashierCartList" style="max-height:46vh;overflow-y:auto;margin-bottom:10px"></div>
            <div id="cashierSettleWrap" style="border-top:1px solid var(--border);padding-top:10px"></div>
        </div>
    </div>`;

    document.querySelectorAll('.cashier-cat-tab').forEach(tab => {
        tab.addEventListener('click', () => cashierSwitchCat(tab.dataset.cat));
    });
    cashierSwitchCat(_cashierCategories[0] || '');
    renderCashierCart();
}

function cashierSwitchCat(cat) {
    document.querySelectorAll('.cashier-cat-tab').forEach(c => {
        const active = c.dataset.cat === cat;
        c.style.background = active ? 'var(--accent)' : '';
        c.style.color = active ? '#fff' : '';
        c.style.borderColor = active ? 'var(--accent)' : '';
    });
    const wrap = document.getElementById('cashierItems');
    if (!wrap) return;
    const items = _cashierItems.filter(i => (i.category || '默认') === cat);
    if (items.length === 0) { wrap.innerHTML = '<div style="color:var(--dim);font-size:13px;padding:20px;text-align:center">该分类暂无商品</div>'; return; }
    let h = '';
    items.forEach(i => {
        const price = parseInt(i.buy_price) || 0;
        const stock = parseInt(i.stock);
        const stockTxt = stock > 0 ? ('库存 ' + stock) : '无限';
        h += '<div style="background:var(--bg);border:1px solid var(--border);border-radius:10px;padding:10px;display:flex;flex-direction:column;gap:6px">';
        h += '<div style="display:flex;align-items:center;gap:8px"><span style="font-size:22px">' + cashierMatIcon(i.material) + '</span><span style="font-size:13px;color:var(--text);font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + escAdmHtml(i.display_name || i.id) + '</span></div>';
        h += '<div style="font-size:12px;color:var(--dim)">' + price + ' 债券 · ' + stockTxt + '</div>';
        h += '<div style="display:flex;align-items:center;gap:6px">';
        h += '<button data-minus="' + i.id + '" style="width:24px;height:24px;border-radius:6px;border:1px solid var(--border);background:var(--card);color:var(--text);cursor:pointer">−</button>';
        h += '<input id="cqty_' + i.id + '" value="1" style="width:42px;text-align:center;padding:4px;background:var(--bg);border:1px solid var(--border);border-radius:6px;color:var(--text);font-size:13px">';
        h += '<button data-plus="' + i.id + '" style="width:24px;height:24px;border-radius:6px;border:1px solid var(--border);background:var(--card);color:var(--text);cursor:pointer">+</button>';
        h += '<button data-add="' + i.id + '" style="flex:1;padding:5px;border:none;border-radius:6px;background:var(--accent);color:#fff;cursor:pointer;font-size:12px;font-weight:600">加入</button>';
        h += '</div></div>';
    });
    wrap.innerHTML = h;
    wrap.querySelectorAll('[data-minus]').forEach(b => b.addEventListener('click', () => cashierItemQty(b.dataset.minus, -1)));
    wrap.querySelectorAll('[data-plus]').forEach(b => b.addEventListener('click', () => cashierItemQty(b.dataset.plus, 1)));
    wrap.querySelectorAll('[data-add]').forEach(b => b.addEventListener('click', () => cashierAddItem(b.dataset.add)));
}

function cashierItemQty(id, delta) {
    const inp = document.getElementById('cqty_' + id);
    if (!inp) return;
    let v = parseInt(inp.value) || 1;
    v = Math.max(1, v + delta);
    inp.value = v;
}

function cashierAddItem(id) {
    const item = _cashierItems.find(i => i.id == id);
    if (!item) return;
    const inp = document.getElementById('cqty_' + id);
    let amount = parseInt(inp ? inp.value : 1) || 1;
    amount = Math.max(1, amount);
    const price = parseInt(item.buy_price) || 0;
    const stock = parseInt(item.stock);
    const exist = _cashierCart.find(c => c.item_id == id);
    if (exist) {
        const max = stock > 0 ? stock : 9999;
        exist.amount = Math.min(exist.amount + amount, max);
    } else {
        _cashierCart.push({item_id: id, name: item.display_name || item.id, price: price, material: item.material || '', stock: stock, amount: amount});
    }
    renderCashierCart();
    toast('已加入代购清单: ' + (item.display_name || item.id), 'ok');
}

function cashierChangeQty(id, delta) {
    const i = _cashierCart.find(c => c.item_id == id);
    if (!i) return;
    const max = i.stock > 0 ? i.stock : 9999;
    i.amount = Math.min(Math.max(i.amount + delta, 1), max);
    renderCashierCart();
}
function cashierRemoveItem(id) {
    _cashierCart = _cashierCart.filter(c => c.item_id != id);
    renderCashierCart();
}
function cashierClearCart() {
    _cashierCart = [];
    renderCashierCart();
}

function cashierColorFee() {
    const c = CASHIER_COLORS.find(x => x.id === _cashierColor);
    return c ? c.price : 0;
}
function cashierSubtotal() {
    return _cashierCart.reduce((s, i) => s + i.price * i.amount, 0);
}
function cashierTotal() {
    const sub = cashierSubtotal();
    const rate = _cashierSettlement === 'shulker' ? _cashierCfg.shulker_rate : _cashierCfg.backpack_rate;
    return Math.round(sub * rate) + (_cashierSettlement === 'shulker' ? cashierColorFee() : 0);
}

function cashierModeHtml(mode, title, total, desc) {
    const sel = _cashierSettlement === mode;
    return '<div onclick="cashierSelectMode(\'' + mode + '\')" style="padding:9px 12px;border:1px solid ' + (sel ? 'var(--accent)' : 'var(--border)') + ';border-radius:10px;cursor:pointer;background:' + (sel ? 'color-mix(in srgb,var(--accent) 10%,transparent)' : 'transparent') + '"><div style="display:flex;justify-content:space-between;align-items:center"><span style="font-size:13px;font-weight:600;color:var(--text)">' + title + '</span><span style="font-size:14px;font-weight:700;color:var(--accent)">' + total + ' 债券</span></div><div style="font-size:11px;color:var(--dim);margin-top:2px">' + desc + '</div></div>';
}

function renderCashierCart() {
    const list = document.getElementById('cashierCartList');
    const settleWrap = document.getElementById('cashierSettleWrap');
    if (!list || !settleWrap) return;
    if (_cashierCart.length === 0) {
        list.innerHTML = '<div style="color:var(--dim);font-size:13px;padding:20px;text-align:center">清单为空，请从左侧添加商品</div>';
    } else {
        let h = '';
        _cashierCart.forEach(i => {
            h += '<div style="display:flex;gap:8px;align-items:center;padding:8px;background:var(--bg);border:1px solid var(--border);border-radius:10px;margin-bottom:6px">';
            h += '<span style="font-size:18px">' + cashierMatIcon(i.material) + '</span>';
            h += '<div style="flex:1;min-width:0"><div style="font-size:13px;color:var(--text);overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + escAdmHtml(i.name) + '</div>';
            h += '<div style="font-size:11px;color:var(--dim)">' + i.price + ' 债券/个</div></div>';
            h += '<div style="display:flex;align-items:center;gap:4px"><button onclick="cashierChangeQty(\'' + i.item_id + '\',-1)" style="width:22px;height:22px;border-radius:6px;border:1px solid var(--border);background:var(--card);color:var(--text);cursor:pointer">−</button>';
            h += '<span style="min-width:22px;text-align:center;font-size:13px">' + i.amount + '</span>';
            h += '<button onclick="cashierChangeQty(\'' + i.item_id + '\',1)" style="width:22px;height:22px;border-radius:6px;border:1px solid var(--border);background:var(--card);color:var(--text);cursor:pointer">+</button></div>';
            h += '<div style="text-align:right;min-width:56px"><div style="font-size:13px;font-weight:600;color:var(--text)">' + (i.price * i.amount) + '</div><button onclick="cashierRemoveItem(\'' + i.item_id + '\')" style="background:none;border:none;color:var(--red);font-size:11px;cursor:pointer">移除</button></div>';
            h += '</div>';
        });
        list.innerHTML = h;
    }

    const sub = cashierSubtotal();
    const total = cashierTotal();
    let html = '';
    html += '<div style="display:flex;justify-content:space-between;font-size:13px;margin-bottom:6px"><span style="color:var(--dim)">原价合计</span><span>' + sub + ' 债券</span></div>';
    html += '<div style="display:flex;flex-direction:column;gap:6px;margin-bottom:8px">';
    const bpTotal = Math.round(sub * _cashierCfg.backpack_rate);
    const shTotal = Math.round(sub * _cashierCfg.shulker_rate) + cashierColorFee();
    html += cashierModeHtml('backpack', '🎒 塞背包', bpTotal, '享 ' + (_cashierCfg.backpack_rate * 10).toFixed(1) + ' 折');
    html += cashierModeHtml('shulker', '📦 潜影盒打包', shTotal, '加价 ' + ((_cashierCfg.shulker_rate - 1) * 100).toFixed(0) + '%' + (cashierColorFee() > 0 ? (' +' + cashierColorFee() + '元') : ''));
    html += '</div>';
    if (_cashierSettlement === 'shulker') {
        html += '<div style="margin-bottom:8px;padding:8px;border:1px solid var(--border);border-radius:8px;background:var(--bg)">';
        html += '<div style="font-size:12px;color:var(--text);margin-bottom:6px">🎨 潜影盒颜色 <span style="color:var(--dim)">（紫色免费，其它+2元）</span></div>';
        html += '<div style="display:flex;flex-wrap:wrap;gap:5px">';
        CASHIER_COLORS.forEach(c => {
            const sel = c.id === _cashierColor;
            const extra = c.price > 0 ? ' (+' + c.price + ')' : '';
            html += '<button onclick="cashierSelectColor(\'' + c.id + '\')" style="display:inline-flex;align-items:center;gap:4px;padding:4px 8px;border-radius:6px;cursor:pointer;font-size:11px;color:var(--text);border:1px solid ' + (sel ? 'var(--accent)' : 'var(--border)') + ';background:' + (sel ? 'color-mix(in srgb,var(--accent) 12%,transparent)' : 'transparent') + '"><span style="width:12px;height:12px;border-radius:3px;display:inline-block;background:' + c.css + ';border:1px solid rgba(255,255,255,.2)"></span>' + c.name + extra + '</button>';
        });
        html += '</div></div>';
    }
    html += '<div style="display:flex;justify-content:space-between;align-items:center;margin:8px 0"><span style="font-size:14px">预估总价</span><span id="cashierTotalLabel" style="font-size:18px;font-weight:700;color:var(--accent)">' + total + ' 债券</span></div>';
    html += '<div style="display:flex;gap:8px"><button class="btn btn-yellow" style="flex:1" onclick="cashierClearCart()">清空</button><button class="btn btn-green" style="flex:2" id="cashierSettleBtn" onclick="cashierSettle()">确认代购</button></div>';
    html += '<div id="cashierBalanceHint" style="font-size:11px;color:var(--dim);margin-top:6px;text-align:center"></div>';
    settleWrap.innerHTML = html;

    const balHint = document.getElementById('cashierBalanceHint');
    if (balHint) {
        if (!_cashierTarget) balHint.textContent = '未选择目标玩家';
        else if (_cashierTargetBalance != null) {
            balHint.textContent = '目标余额: ' + _cashierTargetBalance + ' 债券' + (_cashierTargetBalance < total ? '（余额不足！）' : '');
            balHint.style.color = _cashierTargetBalance < total ? 'var(--red)' : 'var(--dim)';
        }
    }
}

function cashierSelectMode(mode) {
    _cashierSettlement = mode;
    renderCashierCart();
}
function cashierSelectColor(id) {
    _cashierColor = id;
    renderCashierCart();
}

async function cashierCheckPlayer() {
    const inp = document.getElementById('cashierPlayer');
    const info = document.getElementById('cashierTargetInfo');
    if (!inp || !info) return;
    const player = inp.value.trim();
    if (!player) { info.textContent = '请输入玩家名'; info.style.color = 'var(--red)'; return; }
    info.textContent = '查询中...';
    info.style.color = 'var(--dim)';
    try {
        const r = await postApi('cashier_player_check', {player: player});
        if (r && r.success && r.data) {
            _cashierTarget = r.data.player;
            _cashierTargetBalance = r.data.balance;
            const online = r.data.online ? ' <span style="color:var(--green)">●在线</span>' : ' <span style="color:var(--dim)">○离线</span>';
            info.innerHTML = '目标玩家: <b style="color:var(--text)">' + escAdmHtml(r.data.player) + '</b>' + online + '　债券余额: <b style="color:var(--accent)">' + r.data.balance + '</b>';
            info.style.color = 'var(--text)';
        } else {
            _cashierTarget = '';
            _cashierTargetBalance = null;
            info.textContent = '查询失败: ' + (r ? r.message : '未知错误');
            info.style.color = 'var(--red)';
        }
    } catch (e) {
        _cashierTarget = '';
        _cashierTargetBalance = null;
        info.textContent = '查询失败: ' + e.message;
        info.style.color = 'var(--red)';
    }
    renderCashierCart();
}

async function cashierSettle() {
    if (_cashierCart.length === 0) { toast('代购清单为空', 'err'); return; }
    if (!_cashierTarget) { toast('请先选择并查询目标玩家', 'err'); return; }
    const total = cashierTotal();
    const sub = cashierSubtotal();
    if (_cashierTargetBalance != null && _cashierTargetBalance < total) {
        toast('目标玩家余额不足（' + _cashierTargetBalance + ' < ' + total + '）', 'err');
        return;
    }
    const lines = _cashierCart.map(i => '· ' + i.name + ' x' + i.amount + ' = ' + (i.price * i.amount) + ' 债券').join('\n');
    const modeName = _cashierSettlement === 'shulker' ? ('潜影盒打包（' + (CASHIER_COLORS.find(c => c.id === _cashierColor) ? CASHIER_COLORS.find(c => c.id === _cashierColor).name : '') + '）') : '塞背包';
    const summary = '目标玩家: ' + _cashierTarget + '\n结算方式: ' + modeName + '\n原价: ' + sub + ' 债券\n预估总价: ' + total + ' 债券\n\n' + lines;
    cashierShowConfirm(total, summary);
}

function cashierShowConfirm(total, summary) {
    const needPwd = total > 1000;
    let overlay = document.getElementById('cashierConfirmOverlay');
    if (overlay) overlay.remove();
    overlay = document.createElement('div');
    overlay.id = 'cashierConfirmOverlay';
    overlay.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,0.5);backdrop-filter:blur(8px);-webkit-backdrop-filter:blur(8px);z-index:6000;display:flex;justify-content:center;align-items:center;animation:glassFadeIn .25s ease';
    overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
    let html = '<div style="width:420px;max-width:92%;max-height:88vh;overflow-y:auto;background:var(--card);border:1px solid rgba(88,166,255,0.25);border-radius:16px;padding:24px;box-shadow:0 12px 48px rgba(0,0,0,0.5)">';
    html += '<h3 style="margin:0 0 12px;color:var(--accent)">🧾 确认代购</h3>';
    html += '<pre style="white-space:pre-wrap;word-break:break-word;font-family:inherit;font-size:13px;color:var(--text);background:var(--bg);border:1px solid var(--border);border-radius:10px;padding:12px;margin:0 0 14px;line-height:1.6">' + escAdmHtml(summary) + '</pre>';
    if (needPwd) {
        html += '<label style="font-size:12px;color:var(--dim);display:block;margin-bottom:4px">⚠️ 金额≥1000债券，需输入管理员密码确认</label>';
        html += '<input id="cashierPwd" type="password" placeholder="管理员密码" style="width:100%;padding:9px 12px;background:var(--bg);border:1px solid var(--border);border-radius:8px;color:var(--text);font-size:14px;margin-bottom:12px" onkeydown="if(event.key===\'Enter\')cashierConfirmBuy()">';
    }
    html += '<div style="display:flex;gap:10px"><button class="btn btn-yellow" style="flex:1" onclick="document.getElementById(\'cashierConfirmOverlay\').remove()">取消</button><button class="btn btn-green" style="flex:2" id="cashierConfirmBtn" onclick="cashierConfirmBuy()">确认代购</button></div>';
    html += '</div>';
    overlay.innerHTML = html;
    document.body.appendChild(overlay);
    if (needPwd) { const p = document.getElementById('cashierPwd'); if (p) p.focus(); }
}

async function cashierConfirmBuy() {
    const total = cashierTotal();
    const pwdEl = document.getElementById('cashierPwd');
    const password = pwdEl ? pwdEl.value : '';
    if (total > 1000 && !password) { toast('请输入管理员密码确认', 'err'); if (pwdEl) pwdEl.focus(); return; }
    const btn = document.getElementById('cashierConfirmBtn');
    if (btn) { btn.disabled = true; btn.textContent = '代购中...'; }
    await cashierDoBuy(total, password);
    const ov = document.getElementById('cashierConfirmOverlay');
    if (ov) ov.remove();
}

async function cashierDoBuy(total, password) {
    try {
        // 1) 获取管理员代购令牌（all 权限，单次使用）
        const tR = await postApi('gen_token', {player: _cashierTarget, purpose: 'all'});
        if (!tR || !tR.success || !tR.data || !tR.data.token) {
            toast('获取代购令牌失败', 'err');
            return;
        }
        const token = tR.data.token;
        const items = _cashierCart.map(i => ({item_id: i.item_id, amount: i.amount}));
        const body = {
            token: token,
            items: JSON.stringify(items),
            settlement: _cashierSettlement,
            player: _cashierTarget,
            password: password || undefined
        };
        if (_cashierSettlement === 'shulker') body.shulker_color = _cashierColor;
        const res = await fetch('api/shop.php?action=buy_cart', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(body)
        });
        const data = await res.json();
        if (data && data.need_password) {
            toast('请重新输入密码确认', 'err');
            cashierShowConfirm(total, '目标玩家: ' + _cashierTarget + '\n（需密码确认）');
            return;
        }
        if (data && data.success) {
            toast('代购成功：' + _cashierTarget + ' 已获得商品', 'ok');
            _cashierCart = [];
            renderCashierCart();
            cashierCheckPlayer();
        } else {
            toast('代购失败: ' + (data ? data.message : '未知错误'), 'err');
        }
    } catch (e) {
        toast('代购异常: ' + e.message, 'err');
    }
}
