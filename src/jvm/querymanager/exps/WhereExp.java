package querymanager.exps;

public class WhereExp extends Exp {

	ConditionExpLists conditions;
	
	public WhereExp(ConditionExpLists conditions){
		this.conditions = conditions;
	}
	@Override
	Result execute() {
		return null;
	}
	public ConditionExpLists getConditions() {
		return conditions;
	}
	public void setConditions(ConditionExpLists conditions) {
		this.conditions = conditions;
	}

}
