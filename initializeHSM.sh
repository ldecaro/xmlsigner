#!/bin/bash
/opt/cloudhsm/bin/cloudhsm_mgmt_util /opt/cloudhsm/etc/cloudhsm_mgmt_util.cfg > /tmp/hsm_init.out <<EOF
loginHSM PRECO admin password
changePswd PRECO admin $1
y
listUsers
quit
EOF
