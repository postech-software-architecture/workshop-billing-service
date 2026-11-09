Feature: Regras de negocio do servico
  Scenario: Comportamento protegido e repetivel
    Given um orcamento pendente do cliente
    When o cliente aprova duas vezes a mesma proposta
    Then a proposta possui uma unica decisao e preserva seus valores
