#!/system/bin/sh
# Undo adbfw.sh — reopens adbd to the whole LAN.
ALLOW_MAC=AA:BB:CC:DD:EE:FF   # your admin machine's MAC
ALLOW_IP=192.168.1.50        # and its IP
while iptables -D INPUT -p tcp --dport 5555 -m mac --mac-source $ALLOW_MAC -j ACCEPT 2>/dev/null; do :; done
while iptables -D INPUT -p tcp --dport 5555 -s $ALLOW_IP -j ACCEPT 2>/dev/null; do :; done
while iptables -D INPUT -p tcp --dport 5555 -j DROP 2>/dev/null; do :; done
