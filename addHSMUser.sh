#!/bin/bash
#Will create the HSM Crypto User user
#@author lddecaro@amazon.com
/opt/cloudhsm/bin/cloudhsm_mgmt_util /opt/cloudhsm/etc/cloudhsm_mgmt_util.cfg <<EOF
loginHSM CO admin $2
createUser CU $1 $2
y
listUsers
quit
EOF
