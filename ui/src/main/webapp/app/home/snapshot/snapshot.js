/**
 * Controller for Preview Pane.
 */

angular
  .module('pipelineAgentApp.home')

  .controller('SnapshotController', function ($scope, $rootScope, _, api, $timeout) {
    var SOURCE_STAGE_TYPE = 'SOURCE',
      PROCESSOR_STAGE_TYPE = 'PROCESSOR',
      TARGET_STAGE_TYPE = 'TARGET',
      snapshotBatchSize = 10,
      captureSnapshotStatusTimer;

    angular.extend($scope, {
      previewSourceOffset: 0,
      previewBatchSize: 10,
      previewData: {},
      stagePreviewData: {
        input: [],
        output: []
      },
      previewDataUpdated: false,
      stepExecuted: false,
      expandAllInputData: false,
      expandAllOutputData: false,

      /**
       * Returns output records produced by input record.
       *
       * @param outputRecords
       * @param inputRecord
       * @returns {*}
       */
      getOutputRecords: function(outputRecords, inputRecord) {
        return _.filter(outputRecords, function(outputRecord) {
          if(outputRecord.header.previousTrackingId === inputRecord.header.trackingId) {
            if(inputRecord.expand) {
              outputRecord.expand = true;
            }
            return true;
          }
        });
      },

      /**
       * Returns error records produced by input record.
       *
       * @param errorRecords
       * @param inputRecord
       * @returns {*}
       */
      getErrorRecords: function(errorRecords, inputRecord) {
        return _.filter(errorRecords, function(errorRecord) {
          if(errorRecord.header.trackingId === inputRecord.header.trackingId) {
            if(inputRecord.expand) {
              errorRecord.expand = true;
            }
            return true;
          }
        });
      },

      /**
       * Set dirty flag to true when record is updated in Preview Mode.
       *
       * @param recordUpdated
       * @param fieldName
       * @param stageInstance
       */
      recordValueUpdated: function(recordUpdated, fieldName, stageInstance) {
        $scope.previewDataUpdated = true;
        recordUpdated.dirty = true;
        recordUpdated.values[fieldName].dirty = true;
      },


      /**
       * Preview Data for previous stage instance.
       *
       * @param stageInstance
       */
      previousStagePreview: function(stageInstance) {
        $scope.changeStageSelection(stageInstance);
      },

      /**
       * Preview Data for next stage instance.
       * @param stageInstance
       * @param inputRecords
       */
      nextStagePreview: function(stageInstance, inputRecords) {
        if($scope.stepExecuted && stageInstance.uiInfo.stageType === PROCESSOR_STAGE_TYPE) {
          $scope.stepPreview(stageInstance, inputRecords);
        } else {
          $scope.changeStageSelection(stageInstance);
        }
      },

      onExpandAllInputData: function() {
        $scope.expandAllInputData = true;
      },

      onCollapseAllInputData: function() {
        $scope.expandAllInputData = false;
      },

      onExpandAllOutputData: function() {
        $scope.expandAllOutputData = true;
      },

      onCollapseAllOutputData: function() {
        $scope.expandAllOutputData = false;
      }

    });

    /**
     * Returns Preview input lane & output lane data for the given Stage Instance.
     *
     * @param previewData
     * @param stageInstance
     * @returns {{input: Array, output: Array}}
     */
    var getPreviewDataForStage = function (previewData, stageInstance) {
      var inputLane = (stageInstance.inputLanes && stageInstance.inputLanes.length) ?
          stageInstance.inputLanes[0] : undefined,
        outputLane = (stageInstance.outputLanes && stageInstance.outputLanes.length) ?
          stageInstance.outputLanes[0] : undefined,
        stagePreviewData = {
          input: [],
          output: [],
          errorRecords: []
        },
        batchData = previewData.snapshot;

      angular.forEach(batchData, function (stageOutput) {
        if (inputLane && stageOutput.output[inputLane] && stageOutput.output) {
          stagePreviewData.input = stageOutput.output[inputLane];
        } else if (outputLane && stageOutput.output[outputLane] && stageOutput.output) {
          stagePreviewData.output = stageOutput.output[outputLane];
          stagePreviewData.errorRecords = stageOutput.errorRecords;
        }
      });

      return stagePreviewData;
    };

    /**
     * Fetch fields information from Preview Data.
     *
     * @param lanePreviewData
     * @returns {Array}
     */
    var getFields = function(lanePreviewData) {
      var recordValues = _.isArray(lanePreviewData) && lanePreviewData.length ? lanePreviewData[0].values : [],
        fields = [];

      angular.forEach(recordValues, function(typeObject, fieldName) {
        fields.push({
          name : fieldName,
          type: typeObject.type,
          sampleValue: typeObject.value
        });
      });

      return fields;
    };

    /**
     * Update Stage Preview Data when stage selection changed.
     *
     * @param stageInstance
     */
    var updateSnapshotDataForStage = function(stageInstance) {
      if($scope.snapshotMode) {
        var stageInstances = $scope.pipelineConfig.stages;

        $scope.stagePreviewData = getPreviewDataForStage($scope.previewData, stageInstance);

        if(stageInstance.inputLanes && stageInstance.inputLanes.length) {
          $scope.previousStageInstances = _.filter(stageInstances, function(instance) {
            return (_.intersection(instance.outputLanes, stageInstance.inputLanes)).length > 0;
          });
        } else {
          $scope.previousStageInstances = [];
        }

        if(stageInstance.outputLanes && stageInstance.outputLanes.length) {
          $scope.nextStageInstances = _.filter(stageInstances, function(instance) {
            return (_.intersection(instance.inputLanes, stageInstance.outputLanes)).length > 0;
          });
        } else {
          $scope.nextStageInstances = [];
        }
      } else {
        //In case of processors and targets run the preview to get input fields
        // if current state of config is previewable.
        if(stageInstance.uiInfo.stageType !== SOURCE_STAGE_TYPE) {
          if(!stageInstance.uiInfo.inputFields || stageInstance.uiInfo.inputFields.length === 0) {
            if($scope.pipelineConfig.previewable) {
              api.pipelineAgent.previewPipeline($scope.activeConfigInfo.name, $scope.previewSourceOffset, $scope.previewBatchSize).
                success(function (previewData) {
                  var stagePreviewData = getPreviewDataForStage(previewData, stageInstance);
                  stageInstance.uiInfo.inputFields = getFields(stagePreviewData.input);
                }).
                error(function(data) {
                  $rootScope.common.errors = [data];
                });
            }
          }
        }
      }
    };

    /**
     * Check for Snapshot Status for every 1 seconds, once done open the snapshot view.
     *
     */
    var checkForCaptureSnapshotStatus = function() {
      captureSnapshotStatusTimer = $timeout(
        function() {
          //console.log( "Pipeline Metrics Timeout executed", Date.now() );
        },
        1000
      );

      captureSnapshotStatusTimer.then(
        function() {
          api.pipelineAgent.getSnapshotStatus()
            .success(function(data) {
              if(data && data.snapshotInProgress === false) {
                console.log('Capturing Snapshot is completed.');


                api.pipelineAgent.getSnapshot($scope.activeConfigInfo.name).
                  success(function(res) {
                    $scope.previewData = res;

                    var firstStageInstance = $scope.pipelineConfig.stages[0];
                    $scope.changeStageSelection(firstStageInstance);
                  }).
                  error(function(data) {
                    $rootScope.common.errors = [data];
                  });



              } else {
                checkForCaptureSnapshotStatus();
              }
            })
            .error(function(data, status, headers, config) {
              $rootScope.common.errors = [data];
            });
        },
        function() {
          console.log( "Timer rejected!" );
        }
      );
    };

    $scope.$on('snapshotPipeline', function(event, nextBatch) {
      api.pipelineAgent.captureSnapshot(snapshotBatchSize).
        then(function() {
          checkForCaptureSnapshotStatus();
        });
    });

    $scope.$on('onStageSelection', function(event, stageInstance) {
      if($scope.snapshotMode) {
        if (stageInstance) {
          updateSnapshotDataForStage(stageInstance);
        } else {
          $scope.stagePreviewData = {
            input: {},
            output: {}
          };
        }
      }
    });

  });