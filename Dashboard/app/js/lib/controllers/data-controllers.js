FLOW.attributeTypeControl = Ember.Object.create({
  content: [
    Ember.Object.create({
      label: "text",
      value: "String"
    }), Ember.Object.create({
      label: "number",
      value: "Double"
    })
  ]
});

FLOW.attributeControl = Ember.ArrayController.create({
  sortProperties: null,
  sortAscending: true,
  content: null,

  setFilteredContent: function () {
    this.set('content', FLOW.store.filter(FLOW.Metric, function (item) {
      return true;
    }));
  },

  // load all Survey Groups
  populate: function () {
    FLOW.store.find(FLOW.Metric);
    this.setFilteredContent();
    this.set('sortProperties', ['name']);
    this.set('sortAscending', true);
  },

  getSortInfo: function () {
    this.set('sortProperties', FLOW.tableColumnControl.get('sortProperties'));
    this.set('sortAscending', FLOW.tableColumnControl.get('sortAscending'));
  }
});

FLOW.surveyInstanceControl = Ember.ArrayController.create({
  sortProperties: ['collectionDate'],
  sortAscending: false,
  selectedSurvey: null,
  content: null,
  sinceArray: [],
  pageNumber: 0,

  populate: function () {
    this.get('sinceArray').pushObject(FLOW.metaControl.get('since'));
    this.set('content', FLOW.store.findQuery(FLOW.SurveyInstance, {}));
  },

  doInstanceQuery: function (surveyId, deviceId, since, beginDate, endDate, submitterName) {
    this.set('content', FLOW.store.findQuery(FLOW.SurveyInstance, {
      'surveyId': surveyId,
      'deviceId': deviceId,
      'since': since,
      'beginDate': beginDate,
      'endDate': endDate,
      'submitterName': submitterName
    }));
  },

  contentChanged: function() {
    var mutableContents = [];

    this.get('arrangedContent').forEach(function(item) {
        mutableContents.pushObject(item);
    });

    this.set('currentContents', mutableContents);
  }.observes('content', 'content.isLoaded'),

  removeInstance: function(instance) {
    this.get('currentContents').forEach(function(item, i, currentContents) {
        if (item.get('id') == instance.get('id')) {
            currentContents.removeAt(i, 1);
        }
    });
  },

  allAreSelected: function (key, value) {
    if (arguments.length === 2) {
      this.setEach('isSelected', value);
      return value;
    } else {
      return !this.get('isEmpty') && this.everyProperty('isSelected', true);
    }
  }.property('@each.isSelected'),

  atLeastOneSelected: function () {
    return this.filterProperty('isSelected', true).get('length');
  }.property('@each.isSelected'),

  // fired from tableColumnView.sort
  getSortInfo: function () {
    this.set('sortProperties', FLOW.tableColumnControl.get('sortProperties'));
    this.set('sortAscending', FLOW.tableColumnControl.get('sortAscending'));
  }
});

FLOW.surveyedLocaleControl = Ember.ArrayController.create({
  sortProperties: ['collectionDate'],
  sortAscending: false,
  selectedSurvey: null,
  content: null,
  sinceArray: [],
  pageNumber: 0,

  populate: function () {
    this.get('sinceArray').pushObject(FLOW.metaControl.get('since'));
    this.set('content', FLOW.store.findQuery(FLOW.SurveyedLocale, {}));
  }
});

FLOW.questionAnswerControl = Ember.ArrayController.create({
  content: null,

  doQuestionAnswerQuery: function (surveyInstanceId) {
    this.set('content', FLOW.store.findQuery(FLOW.QuestionAnswer, {
      'surveyInstanceId': surveyInstanceId
    }));
  }
});
