#!/system/bin/sh
# Restrict adbd (TCP 5555) to the admin workstation.
#
# This device runs a userdebug build with ro.debuggable=1 and no
# /data/misc/adb/adb_keys, so adbd accepts ANY client on the LAN with no key
# authorisation at all. ro.adb.secure is a read-only build prop and cannot be
# turned on at runtime, so the restriction has to live at the network layer.
#
# Matching on MAC rather than IP deliberately: the workstation is on DHCP, and
# a new lease would otherwise lock everyone out of a device that can then only
# be recovered over USB. The IP is allowed too, as a second way in while the
# lease holds.
ALLOW_MAC=AA:BB:CC:DD:EE:FF   # your admin machine's MAC
ALLOW_IP=192.168.1.50        # and its IP

# Idempotent: drop any previous copy of these rules first.
while iptables -D INPUT -p tcp --dport 5555 -m mac --mac-source $ALLOW_MAC -j ACCEPT 2>/dev/null; do :; done
while iptables -D INPUT -p tcp --dport 5555 -s $ALLOW_IP -j ACCEPT 2>/dev/null; do :; done
while iptables -D INPUT -p tcp --dport 5555 -j DROP 2>/dev/null; do :; done

# Last rule added ends up first, so the ACCEPTs sit above the DROP.
iptables -I INPUT 1 -p tcp --dport 5555 -j DROP
iptables -I INPUT 1 -p tcp --dport 5555 -s $ALLOW_IP -j ACCEPT
iptables -I INPUT 1 -p tcp --dport 5555 -m mac --mac-source $ALLOW_MAC -j ACCEPT
