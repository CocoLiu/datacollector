/**
 * Controller for History.
 */

angular
  .module('pipelineAgentApp.home')
  .controller('HistoryController', function ($rootScope, $scope, _, api) {

    angular.extend($scope, {
      showLoading: false,
      runHistory: [],

      refreshHistory: function() {
        updateHistory($scope.activeConfigInfo.name);
      }
    });

    var updateHistory = function(pipelineName) {
      $scope.showLoading = true;
      api.pipelineAgent.getHistory(pipelineName).
        success(function(res) {
          if(res && res.length) {
            $scope.runHistory = res;
          } else {
            $scope.runHistory = [];
          }
          $scope.showLoading = false;
        }).
        error(function(data) {
          $scope.showLoading = false;
          $rootScope.common.errors = [data];
        });
    };

    $scope.$watch('pipelineConfig.info.name', function() {
      if($scope.pipelineConfig) {
        updateHistory($scope.pipelineConfig.info.name);
      }
    });

  });