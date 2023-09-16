#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# shellcheck source=dev-support/ci/lib/_script_init.sh
. dev-support/ci/lib/_script_init.sh

BASIC_CHECKS=$(grep -r '^#checks:basic' hadoop-ozone/dev-support/checks \
 | sort -u | cut -f1 -d':' | rev | cut -f1 -d'/' | rev | cut -f1 -d'.')

echo "All checks value..."
echo $ALL_BASIC_CHECKS

ALL_BASIC_CHECKS="${ALL_BASIC_CHECKS[@]#\[}"
ALL_BASIC_CHECKS="${ALL_BASIC_CHECKS[@]%\]}"

# Step 3: Replace commas with spaces to form a space-delimited list
SPACE_DELIMITED_ALL_CHECKS=$(echo "$ALL_BASIC_CHECKS" | tr -d '"' | tr ',' ' ')

echo "Space-delimited list: $SPACE_DELIMITED_ALL_CHECKS"

echo "Basic checks....."
echo $BASIC_CHECKS

UNIT_CHECKS=$(grep -r '^#checks:unit' hadoop-ozone/dev-support/checks \
 | sort -u | cut -f1 -d':' | rev | cut -f1 -d'/' | rev | cut -f1 -d'.')

echo "Unit checks....."
echo $UNIT_CHECKS

#BASIC_CHECKS="author bats checkstyle docs findbugs rat"
#UNIT_CHECKS="native unit"
#ALL_BASIC_CHECKS="author bats checkstyle docs findbugs native rat unit"

if [[ -n "${SPACE_DELIMITED_ALL_CHECKS}" ]]; then
    SPACE_DELIMITED_ALL_CHECKS=" ${SPACE_DELIMITED_ALL_CHECKS[*]} "     # add framing blanks
    for item in ${BASIC_CHECKS[@]}; do
      if [[ $SPACE_DELIMITED_ALL_CHECKS =~ " $item " ]] ; then          # use $item as regexp
        basic+=($item)
      fi
    done
    initialization::ga_output basic-checks \
        "$(initialization::parameters_to_json ${basic[@]})"

    for item in ${UNIT_CHECKS[@]}; do
      if [[ $SPACE_DELIMITED_ALL_CHECKS =~ " $item " ]] ; then    # use $item as regexp
        unit+=($item)
      fi
    done
    initialization::ga_output basic-unit \
        "$(initialization::parameters_to_json ${unit[@]})"
fi

