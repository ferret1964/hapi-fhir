package ca.uhn.fhir.jpa.dao.expunge;

import ca.uhn.fhir.jpa.api.config.DaoConfig;
import ca.uhn.fhir.jpa.api.model.DeleteMethodOutcome;
import ca.uhn.fhir.jpa.batch.BatchJobsConfig;
import ca.uhn.fhir.jpa.dao.r4.BaseJpaR4Test;
import ca.uhn.fhir.jpa.model.util.JpaConstants;
import ca.uhn.fhir.jpa.partition.SystemRequestDetails;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.test.utilities.BatchJobHelper;
import ca.uhn.fhir.util.BundleBuilder;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class DeleteExpungeDaoTest extends BaseJpaR4Test {

	@Autowired
	DaoConfig myDaoConfig;
	@Autowired
	BatchJobHelper myBatchJobHelper;

	@BeforeEach
	public void before() {
		myDaoConfig.setAllowMultipleDelete(true);
		myDaoConfig.setExpungeEnabled(true);
		myDaoConfig.setDeleteExpungeEnabled(true);
		myDaoConfig.setInternalSynchronousSearchSize(new DaoConfig().getInternalSynchronousSearchSize());

	}

	@AfterEach
	public void after() {
		DaoConfig daoConfig = new DaoConfig();
		myDaoConfig.setAllowMultipleDelete(daoConfig.isAllowMultipleDelete());
		myDaoConfig.setExpungeEnabled(daoConfig.isExpungeEnabled());
		myDaoConfig.setDeleteExpungeEnabled(daoConfig.isDeleteExpungeEnabled());
	}

	@Test
	public void testDeleteExpungeThrowExceptionIfLink() {
		Organization organization = new Organization();
		organization.setName("FOO");
		IIdType organizationId = myOrganizationDao.create(organization).getId().toUnqualifiedVersionless();

		Patient patient = new Patient();
		patient.setManagingOrganization(new Reference(organizationId));
		IIdType patientId = myPatientDao.create(patient).getId().toUnqualifiedVersionless();

		try {
			myOrganizationDao.deleteByUrl("Organization?" + JpaConstants.PARAM_DELETE_EXPUNGE + "=true", mySrd);
			fail();
		} catch (InvalidRequestException e) {

			assertEquals(e.getMessage(), "DELETE with _expunge=true failed.  Unable to delete " + organizationId.toVersionless() + " because " + patientId.toVersionless() + " refers to it via the path Patient.managingOrganization");
		}
	}


	@Test
	public void testDeleteWithExpungeFailsIfConflictsAreGeneratedByMultiplePartitions() {
		//See https://github.com/hapifhir/hapi-fhir/issues/2661

		//Given
		BundleBuilder builder = new BundleBuilder(myFhirCtx);
		for (int i = 0; i < 20; i++) {
			Organization o = new Organization();
			o.setId("Organization/O-" + i);
			Patient p = new Patient();
			p.setId("Patient/P-" + i);
			p.setManagingOrganization(new Reference(o.getId()));
			builder.addTransactionUpdateEntry(o);
			builder.addTransactionUpdateEntry(p);
		}
		mySystemDao.transaction(new SystemRequestDetails(), (Bundle) builder.getBundle());

		//When
		myDaoConfig.setExpungeBatchSize(10);
		try {
			myOrganizationDao.deleteByUrl("Organization?" + JpaConstants.PARAM_DELETE_EXPUNGE + "=true", mySrd);
			fail();
		} catch (InvalidRequestException e) {
			//Then
			assertThat(e.getMessage(), is(containsString("DELETE with _expunge=true failed.  Unable to delete ")));
		}
	}

	@Test
	public void testDeleteExpungeRespectsExpungeBatchSize() {
		//Given
		myDaoConfig.setExpungeBatchSize(1);
		Patient patient = new Patient();
		myPatientDao.create(patient);
		Patient otherPatient = new Patient();
		myPatientDao.create(otherPatient);

		//When
		OperationOutcome operationOutcome = (OperationOutcome) myPatientDao.deleteByUrl("Patient?" + JpaConstants.PARAM_DELETE_EXPUNGE + "=true", mySrd).getOperationOutcome();

		assertThat(operationOutcome.getIssueFirstRep().getDiagnostics(), startsWith("Delete job submitted"));
		awaitJobCompletion();

		// FIXME Assert on number of reads and writes

		IBundleProvider remaining = myPatientDao.search(new SearchParameterMap().setLoadSynchronous(true));

		//Then
		assertThat(remaining.size(), is(equalTo(1)));

		//When
		myPatientDao.deleteByUrl("Patient?" + JpaConstants.PARAM_DELETE_EXPUNGE + "=true", mySrd);
		awaitJobCompletion();

		remaining = myPatientDao.search(new SearchParameterMap().setLoadSynchronous(true));

		//Then
		assertThat(remaining.size(), is(equalTo(0)));
	}

	private List<JobExecution> awaitJobCompletion() {
		return myBatchJobHelper.awaitAllBulkJobCompletions(BatchJobsConfig.DELETE_EXPUNGE_JOB_NAME);
	}

	@Test
	public void testDeleteExpungeNoThrowExceptionWhenLinkInSearchResults() {
		Patient mom = new Patient();
		IIdType momId = myPatientDao.create(mom).getId().toUnqualifiedVersionless();

		Patient child = new Patient();
		List<Patient.PatientLinkComponent> link;
		child.addLink().setOther(new Reference(mom));
		IIdType childId = myPatientDao.create(child).getId().toUnqualifiedVersionless();

		DeleteMethodOutcome outcome = myPatientDao.deleteByUrl("Patient?" + JpaConstants.PARAM_DELETE_EXPUNGE + "=true", mySrd);
		awaitJobCompletion();

		// FIXME KHS add these outputs to job execution context
		assertEquals(2, outcome.getExpungedResourcesCount());
		assertEquals(7, outcome.getExpungedEntitiesCount());
	}

}
