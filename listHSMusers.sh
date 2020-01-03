#!/bin/bash
/opt/cloudhsm/bin/cloudhsm_mgmt_util /opt/cloudhsm/etc/cloudhsm_mgmt_util.cfg > /tmp/hsm_users.out <<EOF
listUsers
quit
EOF
