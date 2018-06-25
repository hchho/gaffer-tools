/*
 * Copyright 2017-2018 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

'use strict';

angular.module('app').component('operationSelector', operationSelector());

function operationSelector() {
    return {
        templateUrl: 'app/query/operation-selector/operation-selector.html',
        controller: OperationSelectorController,
        controllerAs: 'ctrl'
    }
}

function OperationSelectorController(operationService, operationSelectorService, queryPage, $mdDialog, $routeParams) {
    var vm = this;

    vm.availableOperations;
    vm.selectedOp;

    var populateOperations = function(availableOperations) {
        vm.availableOperations = availableOperations
        var selected = queryPage.getSelectedOperation();
        if (selected)  {
            vm.selectedOp = selected;
        } else {
            vm.selectedOp = vm.availableOperations[0];
            vm.updateModel();
        }

        // allow 'op' to be used as a shorthand
        if($routeParams.op) {
            $routeParams.operation = $routeParams.op;
        }

        if($routeParams.operation) {
            var opParam = $routeParams.operation.replace(/[\W_]+/g, "").toLowerCase();
            for(var i in vm.availableOperations) {
                if(vm.availableOperations[i].name.replace(/[\W_]+/g, "").toLowerCase() === opParam) {
                    vm.selectedOp = vm.availableOperations[i];
                    vm.updateModel();
                    break;
                }
            }
        }
    }

    vm.getLabel = function() {
        if (vm.selectedOp) {
            return vm.selectedOp.name;
        }
        return "Select an operation";
    }

    vm.$onInit = function() {
        operationSelectorService.shouldLoadNamedOperationsOnStartup().then(function(yes) {
            if (yes) {
                operationService.reloadOperations().then(populateOperations);
            } else {
                operationService.getAvailableOperations().then(populateOperations);
            }
        });
    }

    vm.updateModel = function() {
        queryPage.setSelectedOperation(vm.selectedOp);
    }

    vm.refreshNamedOperations = function() {
        operationService.reloadNamedOperations(true).then(function(availableOps) {
            vm.availableOperations = availableOps;
        });
    }
}
