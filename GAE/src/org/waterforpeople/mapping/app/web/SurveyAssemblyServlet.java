/*
 *  Copyright (C) 2010-2012 Stichting Akvo (Akvo Foundation)
 *
 *  This file is part of Akvo FLOW.
 *
 *  Akvo FLOW is free software: you can redistribute it and modify it under the terms of
 *  the GNU Affero General Public License (AGPL) as published by the Free Software Foundation,
 *  either version 3 of the License or any later version.
 *
 *  Akvo FLOW is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Affero General Public License included below for more details.
 *
 *  The full license text can also be seen at <http://www.gnu.org/licenses/agpl.html>.
 */

package org.waterforpeople.mapping.app.web;


import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.bind.JAXBException;

import org.apache.commons.lang.StringEscapeUtils;
import org.apache.log4j.Logger;
import org.waterforpeople.mapping.app.web.dto.SurveyAssemblyRequest;

import com.gallatinsystems.common.domain.UploadStatusContainer;
import com.gallatinsystems.common.objectstore.ObjectStore;
import com.gallatinsystems.common.objectstore.ObjectStore.Container;
import com.gallatinsystems.common.util.PropertyUtil;
import com.gallatinsystems.common.util.ZipUtil;
import com.gallatinsystems.framework.rest.AbstractRestApiServlet;
import com.gallatinsystems.framework.rest.RestRequest;
import com.gallatinsystems.framework.rest.RestResponse;
import com.gallatinsystems.messaging.dao.MessageDao;
import com.gallatinsystems.messaging.domain.Message;
import com.gallatinsystems.survey.dao.QuestionDao;
import com.gallatinsystems.survey.dao.QuestionGroupDao;
import com.gallatinsystems.survey.dao.SurveyDAO;
import com.gallatinsystems.survey.dao.SurveyGroupDAO;
import com.gallatinsystems.survey.dao.SurveyUtils;
import com.gallatinsystems.survey.dao.TranslationDao;
import com.gallatinsystems.survey.domain.Question;
import com.gallatinsystems.survey.domain.QuestionGroup;
import com.gallatinsystems.survey.domain.QuestionHelpMedia;
import com.gallatinsystems.survey.domain.QuestionOption;
import com.gallatinsystems.survey.domain.ScoringRule;
import com.gallatinsystems.survey.domain.Survey;
import com.gallatinsystems.survey.domain.SurveyGroup;
import com.gallatinsystems.survey.domain.Translation;
import com.gallatinsystems.survey.domain.xml.AltText;
import com.gallatinsystems.survey.domain.xml.Dependency;
import com.gallatinsystems.survey.domain.xml.Help;
import com.gallatinsystems.survey.domain.xml.ObjectFactory;
import com.gallatinsystems.survey.domain.xml.Option;
import com.gallatinsystems.survey.domain.xml.Options;
import com.gallatinsystems.survey.domain.xml.Score;
import com.gallatinsystems.survey.domain.xml.Scoring;
import com.gallatinsystems.survey.domain.xml.ValidationRule;
import com.gallatinsystems.survey.xml.SurveyXMLAdapter;
import com.google.appengine.api.backends.BackendServiceFactory;

public class SurveyAssemblyServlet extends AbstractRestApiServlet {
	private static final Logger log = Logger
			.getLogger(SurveyAssemblyServlet.class.getName());

	private static final int BACKEND_QUESTION_THRESHOLD = 80;
	private static final String BACKEND_PUBLISH_PROP = "backendpublish";
	private static final long serialVersionUID = -6044156962558183224L;
	private static final String OPTION_RENDER_MODE_PROP = "optionRenderMode";
	public static final String FREE_QUESTION_TYPE = "free";
	public static final String OPTION_QUESTION_TYPE = "option";
	public static final String GEO_QUESTION_TYPE = "geo";
	public static final String VIDEO_QUESTION_TYPE = "video";
	public static final String PHOTO_QUESTION_TYPE = "photo";
	public static final String SCAN_QUESTION_TYPE = "scan";
	public static final String STRENGTH_QUESTION_TYPE = "strength";
	public static final String DATE_QUESTION_TYPE = "date";
	
	private ObjectStore mObjectStore;
	private String mSurveysContainer;

	private Random randomNumber = new Random();
	
	public SurveyAssemblyServlet() {
		mObjectStore = ObjectStore.instantiate();
		mSurveysContainer = ObjectStore.getContainerName(Container.SURVEYS);
	}

	@Override
	protected RestRequest convertRequest() throws Exception {
		HttpServletRequest req = getRequest();
		RestRequest restRequest = new SurveyAssemblyRequest();
		restRequest.populateFromHttpRequest(req);
		return restRequest;
	}

	@Override
	protected RestResponse handleRequest(RestRequest req) throws Exception {
		RestResponse response = new RestResponse();
		SurveyAssemblyRequest importReq = (SurveyAssemblyRequest) req;
		if (SurveyAssemblyRequest.ASSEMBLE_SURVEY.equalsIgnoreCase(importReq.getAction())) {
			QuestionDao questionDao = new QuestionDao();
			boolean useBackend = false;
			// make sure we're not already running on a backend and that we are
			// allowed to use one
			if (!importReq.getIsForwarded()
					&& "true".equalsIgnoreCase(PropertyUtil
							.getProperty(BACKEND_PUBLISH_PROP))) {
				// if we're allowed to use a backend, then check to see if we
				// need to (based on survey size)
				List<Question> questionList = questionDao
						.listQuestionsBySurvey(importReq.getSurveyId());
				if (questionList != null
						&& questionList.size() > BACKEND_QUESTION_THRESHOLD) {
					useBackend = true;
				}
			}
			if (useBackend) {
				com.google.appengine.api.taskqueue.TaskOptions options = com.google.appengine.api.taskqueue.TaskOptions.Builder
						.withUrl("/app_worker/surveyassembly")
						.param(SurveyAssemblyRequest.ACTION_PARAM,
								SurveyAssemblyRequest.ASSEMBLE_SURVEY)
						.param(SurveyAssemblyRequest.IS_FWD_PARAM, "true")
						.param(SurveyAssemblyRequest.SURVEY_ID_PARAM,
								importReq.getSurveyId().toString());
				// change the host so the queue invokes the backend
				options = options
						.header("Host",
								BackendServiceFactory.getBackendService()
										.getBackendAddress("dataprocessor"));
				com.google.appengine.api.taskqueue.Queue queue = com.google.appengine.api.taskqueue.QueueFactory
						.getQueue("surveyAssembly");
				queue.add(options);
			} else {
				// assembleSurvey(importReq.getSurveyId());
				assembleSurveyOnePass(importReq.getSurveyId());
			}

			List<Long> ids = new ArrayList<Long>();
			ids.add(importReq.getSurveyId());
			SurveyUtils.notifyReportService(ids, "invalidate");
		} else {
			throw new RuntimeException("Action not implemented: " + req.getAction());
		}
			

		return response;
	}

	@Override
	protected void writeOkResponse(RestResponse resp) throws Exception {
		HttpServletResponse httpResp = getResponse();
		httpResp.setStatus(HttpServletResponse.SC_OK);
		// httpResp.setContentType("text/plain");
		httpResp.getWriter().print("OK");
		httpResp.flushBuffer();
	}

	private void assembleSurveyOnePass(Long surveyId) {
		/**************
		 * 1, Select survey based on surveyId 2. Retrieve all question groups
		 * fire off queue tasks
		 */
		log.warn("Starting assembly of " + surveyId);
		// Swap with proper UUID
		SurveyDAO surveyDao = new SurveyDAO();
		Survey s = surveyDao.getById(surveyId);
		SurveyGroupDAO surveyGroupDao = new SurveyGroupDAO();
		SurveyGroup sg = surveyGroupDao.getByKey(s.getSurveyGroupId());
		Long transactionId = randomNumber.nextLong();
		String surveyHeader = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><survey";
		String lang = "en";
		if (s != null && s.getDefaultLanguageCode() != null) {
			lang = s.getDefaultLanguageCode();
		}
		final String version = s.getVersion() == null ? "" : "version='"
				+ s.getVersion() + "'";
		surveyHeader += " defaultLanguageCode='" + lang + "' " + version + ">";
		String surveyFooter = "</survey>";
		QuestionGroupDao qgDao = new QuestionGroupDao();
		TreeMap<Integer, QuestionGroup> qgList = qgDao
				.listQuestionGroupsBySurvey(surveyId);
		if (qgList != null) {
			StringBuilder surveyXML = new StringBuilder();
			surveyXML.append(surveyHeader);
			for (QuestionGroup item : qgList.values()) {
				log.warn("Assembling group " + item.getKey().getId()
						+ " for survey " + surveyId);
				surveyXML.append(buildQuestionGroupXML(item));
			}

			surveyXML.append(surveyFooter);
			log.warn("Uploading " + surveyId);
			UploadStatusContainer uc = uploadSurveyXML(surveyId,
					surveyXML.toString());
			Message message = new Message();
			message.setActionAbout("surveyAssembly");
			message.setObjectId(surveyId);
			message.setObjectTitle(sg.getCode() + " / " + s.getName());
			// String messageText = CONSTANTS.surveyPublishOkMessage() + " "
			// + url;
			if (uc.getUploadedFile() && uc.getUploadedZip()) {
				// increment the version so devices know to pick up the changes
				log.warn("Finishing assembly of " + surveyId);
				surveyDao.incrementVersion(surveyId);
				s.setStatus(Survey.Status.PUBLISHED);
				surveyDao.save(s);
				String messageText = "Published.  Please check: " + uc.getUrl();
				message.setShortMessage(messageText);
				if (qgList != null && qgList.size() > 0) {
					for (QuestionGroup g : qgList.values()) {
						if (g.getPath() != null) {
							message.setObjectTitle(g.getPath());
							break;
						}
					}
				}
				message.setTransactionUUID(transactionId.toString());
				MessageDao messageDao = new MessageDao();
				messageDao.save(message);
			} else {
				// String messageText =
				// CONSTANTS.surveyPublishErrorMessage();
				String messageText = "Failed to publish: " + surveyId + "\n"
						+ uc.getMessage();
				message.setTransactionUUID(transactionId.toString());
				message.setShortMessage(messageText);
				MessageDao messageDao = new MessageDao();
				messageDao.save(message);
			}
			log.warn("Completed onepass assembly method for " + surveyId);
		}
	}

	public UploadStatusContainer uploadSurveyXML(Long surveyId, String surveyXML) {
		String document = surveyXML;
		
		boolean uploadedFile = false;
		boolean	uploadedZip = false;
		try {
			uploadedFile = mObjectStore.uploadFile(mSurveysContainer,
					surveyId + ".xml", surveyXML.getBytes());

			ByteArrayOutputStream os = ZipUtil.generateZip(document, surveyId
					+ ".xml");
			
			uploadedZip = mObjectStore.uploadFile(mSurveysContainer,
					surveyId + ".zip", os.toByteArray());
		} catch (IOException e) {
			log.error(e.getMessage(), e);
		}

		UploadStatusContainer uc = new UploadStatusContainer();
		uc.setUploadedFile(uploadedFile);
		uc.setUploadedZip(uploadedZip);
		uc.setUrl(ObjectStore.getApiUrl() + "/" + mSurveysContainer + "/" + surveyId + ".xml");
		return uc;
	}

	public String buildQuestionGroupXML(QuestionGroup item) {
		QuestionDao questionDao = new QuestionDao();
		QuestionGroupDao questionGroupDao = new QuestionGroupDao();
		QuestionGroup group = questionGroupDao.getByKey(item.getKey().getId());
		TreeMap<Integer, Question> questionList = questionDao
				.listQuestionsByQuestionGroup(item.getKey().getId(), true);

		StringBuilder sb = new StringBuilder("<questionGroup><heading>")
				.append(StringEscapeUtils.escapeXml(group.getCode())).append(
						"</heading>");

		if (questionList != null) {
			for (Question q : questionList.values()) {
				sb.append(marshallQuestion(q));
			}
		}
		return sb.toString() + "</questionGroup>";
	}

	private String marshallQuestion(Question q) {
		SurveyXMLAdapter sax = new SurveyXMLAdapter();
		ObjectFactory objFactory = new ObjectFactory();
		com.gallatinsystems.survey.domain.xml.Question qXML = objFactory
				.createQuestion();
		qXML.setId(new String("" + q.getKey().getId() + ""));
		// ToDo fix
		qXML.setMandatory("false");
		if (q.getText() != null) {
			com.gallatinsystems.survey.domain.xml.Text t = new com.gallatinsystems.survey.domain.xml.Text();
			t.setContent(q.getText());
			qXML.setText(t);
		}
		List<Help> helpList = new ArrayList<Help>();
		// this is here for backward compatibility
		// however, we don't use the helpMedia at the moment
		if (q.getTip() != null) {
			Help tip = new Help();
			com.gallatinsystems.survey.domain.xml.Text t = new com.gallatinsystems.survey.domain.xml.Text();
			t.setContent(q.getTip());
			tip.setText(t);
			tip.setType("tip");
			if (q.getTip() != null && q.getTip().trim().length() > 0
					&& !"null".equalsIgnoreCase(q.getTip().trim())) {
				
				TranslationDao tDao = new TranslationDao();
				Map<String, Translation> tipTrans = tDao.findTranslations(Translation.ParentType.QUESTION_TIP,q.getKey().getId());
				// any translations for question tooltip?

				List<AltText> translationList = new ArrayList<AltText>();
				for (Translation trans : tipTrans
						.values()) {
					AltText aText = new AltText();
					aText.setContent(trans.getText());
					aText.setLanguage(trans.getLanguageCode());
					aText.setType("translation");
					translationList.add(aText);
				}
				if (translationList.size() > 0) {
					tip.setAltText(translationList);
				}
				helpList.add(tip);
			}
		}
		if (q.getQuestionHelpMediaMap() != null) {
			for (QuestionHelpMedia helpItem : q.getQuestionHelpMediaMap()
					.values()) {
				Help tip = new Help();
				com.gallatinsystems.survey.domain.xml.Text t = new com.gallatinsystems.survey.domain.xml.Text();
				t.setContent(helpItem.getText());
				if (helpItem.getType() == QuestionHelpMedia.Type.TEXT) {
					tip.setType("tip");
				} else {
					tip.setType(helpItem.getType().toString().toLowerCase());
					tip.setValue(helpItem.getResourceUrl());
				}
				if (helpItem.getTranslationMap() != null) {
					List<AltText> translationList = new ArrayList<AltText>();
					for (Translation trans : helpItem.getTranslationMap()
							.values()) {
						AltText aText = new AltText();
						aText.setContent(trans.getText());
						aText.setLanguage(trans.getLanguageCode());
						aText.setType("translation");
						translationList.add(aText);
					}
					if (translationList.size() > 0) {
						tip.setAltText(translationList);
					}
				}
				helpList.add(tip);
			}
		}
		if (helpList.size() > 0) {
			qXML.setHelp(helpList);
		}

		boolean hasValidation = false;
		if (q.getIsName() != null && q.getIsName()) {
			ValidationRule validationRule = objFactory.createValidationRule();
			validationRule.setValidationType("name");
			qXML.setValidationRule(validationRule);
			hasValidation = true;
		} else if (q.getType() == Question.Type.NUMBER
				&& (q.getAllowDecimal() != null || q.getAllowSign() != null
						|| q.getMinVal() != null || q.getMaxVal() != null)) {
			ValidationRule validationRule = objFactory.createValidationRule();
			validationRule.setValidationType("numeric");
			validationRule.setAllowDecimal(q.getAllowDecimal() != null ? q
					.getAllowDecimal().toString().toLowerCase() : "false");
			validationRule.setSigned(q.getAllowSign() != null ? q
					.getAllowSign().toString().toLowerCase() : "false");
			if (q.getMinVal() != null) {
				validationRule.setMinVal(q.getMinVal().toString());
			}
			if (q.getMaxVal() != null) {
				validationRule.setMaxVal(q.getMaxVal().toString());
			}
			qXML.setValidationRule(validationRule);
			hasValidation = true;
		}

		qXML.setAltText(formAltText(q.getTranslationMap()));

		if (q.getType().equals(Question.Type.FREE_TEXT)) {
			qXML.setType(FREE_QUESTION_TYPE);
		} else if (q.getType().equals(Question.Type.GEO)) {
			qXML.setType(GEO_QUESTION_TYPE);
		} else if (q.getType().equals(Question.Type.NUMBER)) {
			qXML.setType(FREE_QUESTION_TYPE);
			if (!hasValidation) {
				ValidationRule vrule = new ValidationRule();
				vrule.setValidationType("numeric");
				vrule.setSigned("false");
				qXML.setValidationRule(vrule);
			}
		} else if (q.getType().equals(Question.Type.OPTION)) {
			qXML.setType(OPTION_QUESTION_TYPE);
		} else if (q.getType().equals(Question.Type.PHOTO)) {
			qXML.setType(PHOTO_QUESTION_TYPE);
		} else if (q.getType().equals(Question.Type.VIDEO)) {
			qXML.setType(VIDEO_QUESTION_TYPE);
		} else if (q.getType().equals(Question.Type.SCAN)) {
			qXML.setType(SCAN_QUESTION_TYPE);
		} else if (q.getType().equals(Question.Type.NAME)) {
			qXML.setType(FREE_QUESTION_TYPE);
			ValidationRule vrule = new ValidationRule();
			vrule.setValidationType("name");
			qXML.setValidationRule(vrule);
		} else if (q.getType().equals(Question.Type.STRENGTH)) {
			qXML.setType(STRENGTH_QUESTION_TYPE);
		} else if (q.getType().equals(Question.Type.DATE)) {
			qXML.setType(DATE_QUESTION_TYPE);
		}

		if (q.getOrder() != null) {
			qXML.setOrder(q.getOrder().toString());
		}
		if (q.getMandatoryFlag() != null) {
			qXML.setMandatory(q.getMandatoryFlag().toString());
		}
		Dependency dependency = objFactory.createDependency();
		if (q.getDependentQuestionId() != null) {
			dependency.setQuestion(q.getDependentQuestionId().toString());
			dependency.setAnswerValue(q.getDependentQuestionAnswer());
			qXML.setDependency(dependency);
		}

		if (q.getQuestionOptionMap() != null
				&& q.getQuestionOptionMap().size() > 0) {
			Options options = objFactory.createOptions();
			if (q.getAllowOtherFlag() != null) {
				options.setAllowOther(q.getAllowOtherFlag().toString());
			}
			if (q.getAllowMultipleFlag() != null) {
				options.setAllowMultiple(q.getAllowMultipleFlag().toString());
			}
			if (options.getAllowMultiple() == null
					|| "false".equals(options.getAllowMultiple())) {
				options.setRenderType(PropertyUtil
						.getProperty(OPTION_RENDER_MODE_PROP));
			}

			ArrayList<Option> optionList = new ArrayList<Option>();
			for (QuestionOption qo : q.getQuestionOptionMap().values()) {
				Option option = objFactory.createOption();
				com.gallatinsystems.survey.domain.xml.Text t = new com.gallatinsystems.survey.domain.xml.Text();
				t.setContent(qo.getText());
				option.addContent(t);
				option.setValue(qo.getCode() != null ? qo.getCode() : qo
						.getText());
				List<AltText> altTextList = formAltText(qo.getTranslationMap());
				if (altTextList != null) {
					for (AltText alt : altTextList) {
						option.addContent(alt);
					}
				}
				optionList.add(option);
			}
			options.setOption(optionList);

			qXML.setOptions(options);
		}

		if (q.getScoringRules() != null) {
			Scoring scoring = new Scoring();

			for (ScoringRule rule : q.getScoringRules()) {
				Score score = new Score();
				if (scoring.getType() == null) {
					scoring.setType(rule.getType().toLowerCase());
				}
				score.setRangeHigh(rule.getRangeMax());
				score.setRangeLow(rule.getRangeMin());
				score.setValue(rule.getValue());
				scoring.addScore(score);
			}
			if (scoring.getScore() != null && scoring.getScore().size() > 0) {
				qXML.setScoring(scoring);
			}
		}

		String questionDocument = null;
		try {
			questionDocument = sax.marshal(qXML);
		} catch (JAXBException e) {
			log.warn("Could not marshal question: " + qXML, e);
		}

		questionDocument = questionDocument
				.replace(
						"<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>",
						"");
		return questionDocument;
	}

	private List<AltText> formAltText(Map<String, Translation> translationMap) {
		List<AltText> altTextList = new ArrayList<AltText>();
		if (translationMap != null) {
			for (Translation lang : translationMap.values()) {
				AltText alt = new AltText();
				alt.setContent(lang.getText());
				alt.setType("translation");
				alt.setLanguage(lang.getLanguageCode());
				altTextList.add(alt);
			}
		}

		return altTextList;
	}

}