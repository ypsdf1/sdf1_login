<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>404 - 页面不存在</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            background: #0a0a0f;
            color: #e0e0e0;
            font-family: 'Segoe UI', system-ui, sans-serif;
            display: flex;
            justify-content: center;
            align-items: center;
            min-height: 100vh;
            overflow: hidden;
        }
        .container {
            text-align: center;
            position: relative;
        }
        .glitch {
            font-size: 120px;
            font-weight: 900;
            color: #ff2d55;
            text-shadow: 0 0 10px #ff2d55, 0 0 40px #ff2d55, 0 0 80px #ff2d55;
            animation: glitch 2s infinite;
            position: relative;
        }
        .glitch::before, .glitch::after {
            content: '404';
            position: absolute;
            top: 0; left: 0;
            width: 100%; height: 100%;
        }
        .glitch::before {
            color: #0ff;
            animation: glitch-top 3s infinite;
            clip-path: polygon(0 0, 100% 0, 100% 33%, 0 33%);
            text-shadow: 0 0 10px #0ff;
        }
        .glitch::after {
            color: #f0f;
            animation: glitch-bottom 2.5s infinite;
            clip-path: polygon(0 67%, 100% 67%, 100% 100%, 0 100%);
            text-shadow: 0 0 10px #f0f;
        }
        @keyframes glitch {
            0%, 90%, 100% { transform: translate(0); }
            92% { transform: translate(-5px, 2px); }
            94% { transform: translate(5px, -2px); }
            96% { transform: translate(-3px, -1px); }
            98% { transform: translate(3px, 1px); }
        }
        @keyframes glitch-top {
            0%, 85%, 100% { transform: translate(0); }
            87% { transform: translate(3px, -2px); }
            89% { transform: translate(-3px, 1px); }
        }
        @keyframes glitch-bottom {
            0%, 88%, 100% { transform: translate(0); }
            90% { transform: translate(-4px, 2px); }
            93% { transform: translate(4px, -1px); }
        }
        .msg {
            font-size: 18px;
            color: #888;
            margin-top: 30px;
            letter-spacing: 2px;
        }
        .links {
            margin-top: 40px;
            display: flex;
            gap: 20px;
            justify-content: center;
        }
        .links a {
            color: #ff2d55;
            text-decoration: none;
            padding: 10px 24px;
            border: 1px solid #ff2d55;
            border-radius: 6px;
            transition: all 0.3s;
        }
        .links a:hover {
            background: #ff2d55;
            color: #fff;
            box-shadow: 0 0 20px rgba(255,45,85,0.4);
        }
        .scanline {
            position: fixed;
            top: 0; left: 0; right: 0;
            height: 2px;
            background: rgba(255,255,255,0.03);
            animation: scan 8s linear infinite;
        }
        @keyframes scan {
            0% { top: 0; }
            100% { top: 100vh; }
        }
    </style>
</head>
<body>
    <div class="scanline"></div>
    <div class="container">
        <div class="glitch">404</div>
        <div class="msg">页面不存在或已被移除</div>
        <div class="links">
            <a href="player.php">玩家商城</a>
            <a href="admin_login.php">管理后台</a>
        </div>
    </div>
</body>
</html>
