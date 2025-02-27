package liquibase.changelog.visitor;

import liquibase.Scope;
import liquibase.change.Change;
import liquibase.change.core.SQLFileChange;
import liquibase.changelog.*;
import liquibase.changelog.filter.ChangeSetFilterResult;
import liquibase.database.Database;
import liquibase.exception.LiquibaseException;
import liquibase.executor.Executor;
import liquibase.executor.ExecutorService;
import liquibase.executor.LoggingExecutor;
import liquibase.logging.mdc.MdcKey;
import liquibase.logging.mdc.customobjects.ChangesetsRolledback;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class RollbackVisitor implements ChangeSetVisitor {

    private Database database;

    private ChangeExecListener execListener;
    private List<ChangesetsRolledback.ChangeSet> processedChangesets = new ArrayList<>();

    /**
     * @deprecated - please use the constructor with ChangeExecListener, which can be null.
     */
    @Deprecated
    public RollbackVisitor(Database database) {
        this.database = database;
    }

    public RollbackVisitor(Database database, ChangeExecListener listener) {
        this(database);
        this.execListener = listener;
    }

    public RollbackVisitor(Database database, ChangeExecListener listener, List<ChangesetsRolledback.ChangeSet> processedChangesets) {
        this(database);
        this.execListener = listener;
        this.processedChangesets = processedChangesets;
    }

    @Override
    public Direction getDirection() {
        return ChangeSetVisitor.Direction.REVERSE;
    }

    @Override
    public void visit(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database, Set<ChangeSetFilterResult> filterResults) throws LiquibaseException {
        logMdcData(changeSet);
        Executor executor = Scope.getCurrentScope().getSingleton(ExecutorService.class).getExecutor("jdbc", database);
        if (! (executor instanceof LoggingExecutor)) {
            Scope.getCurrentScope().getUI().sendMessage("Rolling Back Changeset: " + changeSet);
        }
        sendRollbackWillRunEvent(changeSet, databaseChangeLog, database);
        try {
            changeSet.rollback(this.database, this.execListener);
        }
        catch (Exception e) {
            fireRollbackFailed(changeSet, databaseChangeLog, database, e);
            throw e;
        }
        this.database.removeRanStatus(changeSet);
        sendRollbackEvent(changeSet, databaseChangeLog, database);
        this.database.commit();
        checkForEmptyRollbackFile(changeSet);
        if (processedChangesets != null) {
            processedChangesets.add(new ChangesetsRolledback.ChangeSet(changeSet.getId(), changeSet.getAuthor(), changeSet.getFilePath(), changeSet.getDeploymentId()));
        }
    }

    protected void fireRollbackFailed(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database, Exception e) {
        if (execListener != null) {
            execListener.rollbackFailed(changeSet, databaseChangeLog, database, e);
        }
    }

    private void checkForEmptyRollbackFile(ChangeSet changeSet) {
        RollbackContainer container = changeSet.getRollback();
        List<Change> changes = container.getChanges();
        if (changes.isEmpty()) {
            return;
        }
        for (Change change : changes) {
            if (! (change instanceof SQLFileChange)) {
                continue;
            }
            String sql = ((SQLFileChange)change).getSql();
            if (sql.length() == 0) {
                Scope.getCurrentScope().getLog(getClass()).info("\nNo rollback logic defined in empty rollback script. Changesets have been removed from\n" +
                                "the DATABASECHANGELOG table but no other logic was performed.");
            }
        }
    }

    private void sendRollbackWillRunEvent(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database) {
        if (execListener != null) {
            execListener.willRollback(changeSet, databaseChangeLog, database);
        }
    }

    private void sendRollbackEvent(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database) {
        if (execListener != null) {
            execListener.rolledBack(changeSet, databaseChangeLog, database);
        }
    }
}
