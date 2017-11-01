# frozen_string_literal: true

require 'spec_helper'

module ElasticAPM
  RSpec.describe Middleware do
    it 'surrounds the request in a transaction' do
      app = Middleware.new(->(_) { [200, {}, ['ok']] })
      status, = app.call(Rack::MockRequest.env_for('/'))

      expect(status).to be 200
      expect(ElasticAPM.client.pending_transactions.length).to be 1
      expect(ElasticAPM.client.current_transaction).to be_nil
    end
  end
end