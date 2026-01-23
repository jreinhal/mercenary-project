# Linux Hardening (MongoDB + SENTINEL)

This guide provides baseline hardening steps for Linux deployments.

## Firewall (UFW)
Allow only required ports and restrict MongoDB to localhost.

Example (MongoDB on same host as app):
```
sudo ufw allow 8080/tcp
sudo ufw allow 11434/tcp
sudo ufw deny 27017/tcp
sudo ufw enable
```

If MongoDB is on a separate host, allow only the app server:
```
sudo ufw allow from <APP_SERVER_IP> to any port 27017 proto tcp
```

## Firewall (firewalld)
```
sudo firewall-cmd --add-port=8080/tcp --permanent
sudo firewall-cmd --add-port=11434/tcp --permanent
sudo firewall-cmd --remove-port=27017/tcp --permanent
sudo firewall-cmd --reload
```

## MongoDB bindIp
Ensure MongoDB listens only on localhost unless you explicitly need remote access:
```
net:
  port: 27017
  bindIp: 127.0.0.1
```

## Systemd service for SENTINEL
Example unit file at /etc/systemd/system/sentinel.service:
```
[Unit]
Description=Sentinel Intelligence Platform
After=network.target

[Service]
Type=simple
User=sentinel
Group=sentinel
WorkingDirectory=/opt/sentinel
EnvironmentFile=/etc/sentinel/sentinel.env
ExecStart=/usr/bin/java -jar /opt/sentinel/sentinel-government-2.2.0.jar
Restart=on-failure
RestartSec=5
LimitNOFILE=65536
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=full
ProtectHome=yes

[Install]
WantedBy=multi-user.target
```

Enable and start:
```
sudo systemctl daemon-reload
sudo systemctl enable sentinel
sudo systemctl start sentinel
sudo systemctl status sentinel
```

## Systemd hardening for MongoDB
Use a drop-in override to apply safe defaults. Create with:
```
sudo systemctl edit mongod
```
Add:
```
[Service]
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=full
ProtectHome=yes
```
Restart:
```
sudo systemctl restart mongod
```

## Notes
- Test hardening changes in staging before production.
- Over-restricting systemd options can prevent MongoDB from starting.
