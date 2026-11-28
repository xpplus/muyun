# Codespaces 自定义镜像无法 SSH 的修复

## 现象

- 能开关机（走 GitHub API）
- SSH 连接失败：`Socket closed` / `Exhausted available authentication methods`

## 原因

官方 Codespaces 镜像预置了：

- 用户 `codespace`，**UID 1000**
- `codespace` 用户**免密 sudo**
- 运行中的 `sshd`（22 端口）
- 家目录 `/home/codespace` 与正确权限

精简镜像 / 自定义 Dockerfile 往往**没有这个用户**，于是 `gh codespace ssh` 无法完成登录。

## 一键修复（在 Codespace 的网页终端里执行）

```bash
bash -c '
set -e
U=codespace
if ! id -u "$U" >/dev/null 2>&1; then
  if id -u 1000 >/dev/null 2>&1; then
    U=$(getent passwd 1000 | cut -d: -f1)
    echo "UID 1000 已被占用，改用现有用户：$U"
  else
    sudo useradd -m -u 1000 -s /bin/bash "$U"
    echo "已创建用户 $U (UID 1000)"
  fi
fi
echo "$U ALL=(ALL) NOPASSWD:ALL" | sudo tee /etc/sudoers.d/90-codespace >/dev/null
sudo chmod 0440 /etc/sudoers.d/90-codespace
sudo mkdir -p /home/$U/.ssh
sudo chown -R $U:$U /home/$U
sudo chmod 700 /home/$U/.ssh
(sudo service ssh start 2>/dev/null || sudo /usr/sbin/sshd 2>/dev/null || true)
echo "修复完成：$U"
'
```

执行后回到牧云，重新点该 Codespace 的 SSH。

## 更彻底的做法（写进 Dockerfile）

```dockerfile
RUN useradd -m -u 1000 -s /bin/bash codespace \
 && echo 'codespace ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/90-codespace \
 && chmod 0440 /etc/sudoers.d/90-codespace \
 && mkdir -p /home/codespace/.ssh \
 && chown -R codespace:codespace /home/codespace \
 && chmod 700 /home/codespace/.ssh
RUN apt-get update && apt-get install -y openssh-server && service ssh start
```

## 验证

```bash
id codespace        # uid=1000(codespace)
sudo -n true        # 免密 sudo 成功
ss -lntp | grep :22 # sshd 在监听
```
