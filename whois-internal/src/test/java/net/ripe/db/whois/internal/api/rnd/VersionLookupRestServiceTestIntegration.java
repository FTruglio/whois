package net.ripe.db.whois.internal.api.rnd;

import net.ripe.db.whois.api.RestTest;
import net.ripe.db.whois.api.rest.domain.Attribute;
import net.ripe.db.whois.api.rest.domain.ErrorMessage;
import net.ripe.db.whois.common.ClockDateTimeProvider;
import net.ripe.db.whois.common.IntegrationTest;
import net.ripe.db.whois.common.Message;
import net.ripe.db.whois.common.Messages;
import net.ripe.db.whois.common.dao.RpslObjectUpdateInfo;
import net.ripe.db.whois.common.dao.jdbc.JdbcRpslObjectDao;
import net.ripe.db.whois.common.dao.jdbc.JdbcRpslObjectUpdateDao;
import net.ripe.db.whois.common.rpsl.RpslAttribute;
import net.ripe.db.whois.common.rpsl.RpslObject;
import net.ripe.db.whois.common.rpsl.RpslObjectBuilder;
import net.ripe.db.whois.internal.AbstractInternalTest;
import net.ripe.db.whois.internal.api.rnd.rest.WhoisInternalResources;
import net.ripe.db.whois.internal.api.rnd.rest.WhoisVersionInternal;
import org.joda.time.LocalDateTime;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.springframework.test.jdbc.JdbcTestUtils;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.MediaType;

import static junit.framework.TestCase.fail;
import static net.ripe.db.whois.common.rpsl.AttributeType.ADDRESS;
import static net.ripe.db.whois.common.rpsl.ObjectType.MNTNER;
import static net.ripe.db.whois.common.rpsl.ObjectType.ORGANISATION;
import static net.ripe.db.whois.common.rpsl.ObjectType.PERSON;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@Category(IntegrationTest.class)
public class VersionLookupRestServiceTestIntegration extends AbstractInternalTest {

    public static final String API_REST_RND_BASEURL = "http://int.db.ripe.net";

    private VersionObjectMapper versionMapper = new VersionObjectMapper(API_REST_RND_BASEURL);

    @Before
    public void setUp() {
        testDateTimeProvider.reset();
        databaseHelper.insertApiKey(apiKey, "/api/rnd", "rnd api key");
        databaseHelper.setSourceAwareDataSource(sourceAwareDataSource);

        // TODO: drop this once we have proper wiring in whois-internal
        databaseHelper.setRpslObjectDao(new JdbcRpslObjectDao(whoisUpdateDataSource, null));
        databaseHelper.setRpslObjectUpdateDao(new JdbcRpslObjectUpdateDao(whoisUpdateDataSource, new ClockDateTimeProvider()));

        setupObjects();
        runUpdateObjectVersions();
    }

    private void setupObjects(){
        final RpslObject mntner = RpslObject.parse("" +
                "mntner:         TEST-MNT\n" +
                "descr:          Maintainer\n" +
                "auth:           SSO person@net.net\n" +
                "auth:           MD5-PW $1$d9fKeTr2$Si7YudNf4rUGmR71n/cqk/ #test\n" +
                "mnt-by:         TEST-MNT\n" +
                "upd-to:         noreply@ripe.net\n" +
                "changed:        noreply@ripe.net 20120101\n" +
                "source:         TEST");

        final RpslObject unrefMntner = RpslObject.parse("" +
                "mntner:         UNREF-MNT\n" +
                "source:         TEST");

        final RpslObject organisation = RpslObject.parse("" +
                "organisation: ORG-AB1-TEST\n" +
                "org-name: My Org\n" +
                "org-type: OTHER\n" +
                "address: street\n" +
                "address: postcode\n" +
                "address: country\n" +
                "admin-c: TP1-TEST\n" +
                "e-mail: test@dev.net\n" +
                "mnt-by: TEST-MNT\n" +
                "changed: test@test.net\n" +
                "source: TEST");

        final RpslObject person = RpslObject.parse("" +
                "person: Test Person\n" +
                "nic-hdl: TP1-TEST\n" +
                "mnt-by: TEST-MNT\n" +
                "changed: test@test.net\n" +
                "source: TEST");

        updateDao.createObject(mntner);
        updateDao.createObject(unrefMntner);
        updateDao.createObject(person);

        final RpslObjectUpdateInfo objectInfo = updateDao.createObject(organisation);

        final LocalDateTime localDateTime = new LocalDateTime();
        testDateTimeProvider.setTime(localDateTime.plusDays(5));
        updateDao.updateObject(objectInfo.getObjectId(),
                new RpslObjectBuilder(organisation)
                        .removeAttributeType(ADDRESS)
                        .addAttributeSorted(new RpslAttribute(ADDRESS, "new address"))
                        .get());
    }

    private void runUpdateObjectVersions() {
        JdbcTestUtils.deleteFromTables(whoisTemplate, "object_reference", "object_version");
        final UpdateObjectVersions updateObjectVersions = new UpdateObjectVersions(objectReferenceUpdateDao, jdbcVersionDao, whoisUpdateDataSource);
        updateObjectVersions.run();
    }

    @Test
    public void references_for_self_referenced_maintainer() {

        final WhoisVersionInternal mntnerV1 = versionMapper.mapVersion(objectReferenceDao.getVersion(MNTNER, "TEST-MNT", 1), "test");
        final WhoisVersionInternal personV1 = versionMapper.mapVersion(objectReferenceDao.getVersion(PERSON, "TP1-TEST", 1), "test");
        final WhoisVersionInternal orgV1 = versionMapper.mapVersion(objectReferenceDao.getVersion(ORGANISATION, "ORG-AB1-TEST", 1), "test");
        final WhoisVersionInternal orgV2 = versionMapper.mapVersion(objectReferenceDao.getVersion(ORGANISATION, "ORG-AB1-TEST", 2), "test");

        final WhoisInternalResources whoisResources = RestTest.target(getPort(), "api/rnd/test/mntner/TEST-MNT/versions/1", null, apiKey)
                .request(MediaType.APPLICATION_JSON)
                .get(WhoisInternalResources.class);

        assertThat(whoisResources.getObject().getAttributes(), hasItems(
                                            new Attribute("auth", "SSO", "Filtered", null, null),
                                            new Attribute("auth", "MD5-PW", "Filtered", null, null)
                                            ));

        assertThat(whoisResources.getVersion(), is(mntnerV1));

        assertThat(whoisResources.getErrorMessages(), is(empty()));
        assertThat(whoisResources.getOutgoing().get(0), is(mntnerV1));
//        assertThat(whoisResources.getIncoming(), containsInAnyOrder(mntnerV1, personV1, orgV1, orgV2));
        assertThat(whoisResources.getIncoming(), hasItem(personV1));
        assertThat(whoisResources.getIncoming(), hasItem(mntnerV1));
//        assertThat(whoisResources.getIncoming(), hasItem(orgV2));
//        assertThat(whoisResources.getIncoming(), hasItem(orgV1));
    }

    @Test
    public void correct_version_of_an_object_is_returned() {
        final WhoisInternalResources orgInHistory = RestTest.target(getPort(), "api/rnd/test/organisation/ORG-AB1-TEST/versions/1", null, apiKey)
                .request(MediaType.APPLICATION_JSON)
                .get(WhoisInternalResources.class);

        assertThat(orgInHistory.getObject().getAttributes(), hasItem(new Attribute("address", "street")));

        final WhoisInternalResources orgInLast = RestTest.target(getPort(), "api/rnd/test/organisation/ORG-AB1-TEST/versions/2", null, apiKey)
                .request(MediaType.APPLICATION_JSON)
                .get(WhoisInternalResources.class);

        assertThat(orgInLast.getObject().getAttributes(), hasItem(new Attribute("address", "new address")));
    }

    @Test
    public void no_incoming_or_outgoing_references() throws Exception {
        final WhoisInternalResources whoisResources = RestTest.target(getPort(), "api/rnd/test/mntner/UNREF-MNT/versions/1", null, apiKey)
                .request(MediaType.APPLICATION_JSON)
                .get(WhoisInternalResources.class);

        assertThat(whoisResources.getObject().getAttributes(), hasSize(2));
        assertThat(whoisResources.getIncoming(), is(nullValue()));
        assertThat(whoisResources.getOutgoing(), is(nullValue()));
    }

    @Test
    public void object_not_found() {
        try {
            RestTest.target(getPort(), "api/rnd/test/mntner/NONEXISTANT/versions/1", null, apiKey)
                    .request(MediaType.APPLICATION_JSON)
                    .get(WhoisInternalResources.class);
            fail();
        } catch (NotFoundException e) {
            final WhoisInternalResources whoisResources = e.getResponse().readEntity(WhoisInternalResources.class);
            assertThat(whoisResources.getErrorMessages(), hasSize(1));
            assertThat(whoisResources.getErrorMessages().get(0).toString(), is("There is no entry for object NONEXISTANT for the supplied version."));
        }
    }

    @Test
    public void version_not_found() {
        try {
            RestTest.target(getPort(), "api/rnd/test/mntner/TEST-MNT/versions/0", null, apiKey)
                    .request(MediaType.APPLICATION_JSON)
                    .get(WhoisInternalResources.class);
            fail();
        } catch (NotFoundException e) {
            final WhoisInternalResources whoisResources = e.getResponse().readEntity(WhoisInternalResources.class);
            assertThat(whoisResources.getErrorMessages(), hasSize(1));
            assertThat(whoisResources.getErrorMessages().get(0).toString(), is("There is no entry for object TEST-MNT for the supplied version."));
        }
    }

    @Test
    public void multiple_updates_in_same_second_adds_warning_message() {
        whoisTemplate.execute("UPDATE last SET timestamp = (SELECT timestamp FROM history where pkey='ORG-AB1-TEST') WHERE pkey='ORG-AB1-TEST' ");

        final WhoisInternalResources whoisResources = RestTest.target(getPort(), "api/rnd/test/organisation/ORG-AB1-TEST/versions/1", null, apiKey)
                .request(MediaType.APPLICATION_JSON)
                .get(WhoisInternalResources.class);

        assertThat(whoisResources.getErrorMessages(), hasSize(1));
        assertThat(whoisResources.getErrorMessages().get(0), is(new ErrorMessage(
                new Message(Messages.Type.WARNING, "There are %s versions of the object for this interval. The last one is displayed.", 2))));
    }
}